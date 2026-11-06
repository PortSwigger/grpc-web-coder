package com.nxenon.grpcweb.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.utilities.CompressionType;
import com.nxenon.grpcweb.analyze.JsAnalyzer;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * Runs JavaScript analysis off the Swing event dispatch thread.
 *
 * <p>Regex scanning of a multi-megabyte bundle takes long enough to freeze Burp's whole UI if done
 * inline, which the BApp Store acceptance criteria call out specifically. Work is queued onto a
 * single worker thread and {@link #shutdown()} stops it when the extension unloads, so no thread
 * outlives the extension.
 */
final class JsAnalysisRunner {

    private final MontoyaApi api;
    private final AnalyzerPanel panel;
    private final ExecutorService executor;

    JsAnalysisRunner(MontoyaApi api, AnalyzerPanel panel) {
        this.api = api;
        this.panel = panel;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "grpc-web-coder-analyzer");
            // A daemon thread cannot keep the JVM alive if shutdown is ever missed.
            thread.setDaemon(true);
            return thread;
        });
    }

    /** Queues analysis of the responses attached to the selected messages. */
    void analyze(List<HttpRequestResponse> selected) {
        if (selected == null || selected.isEmpty()) {
            api.logging().logToOutput("gRPC-Web Coder: nothing selected to analyze.");
            return;
        }
        // Only cheap metadata is read here: the label, the body handle and one header. Holding
        // HttpRequestResponse objects across threads and across time is what the "large projects"
        // criterion warns against, so those are resolved now and nothing else crosses.
        //
        // Decompression is deliberately NOT done here. Inflating a multi-megabyte bundle takes
        // long enough to freeze Burp's UI, and this method runs on the event dispatch thread.
        for (HttpRequestResponse requestResponse : selected) {
            String source = describe(requestResponse);
            ByteArray body = extractBody(requestResponse);
            if (body == null) {
                api.logging().logToOutput(
                        "gRPC-Web Coder: " + source + " has no response body to analyze.");
                continue;
            }
            String encoding = header(requestResponse.response(), "Content-Encoding");
            executor.execute(() -> run(source, encoding, body));
        }
    }

    private void run(String source, String encoding, ByteArray body) {
        try {
            String script = decompress(source, encoding, body);

            // Reported separately from "found nothing": an oversized bundle is the one case where
            // the user can act on the answer, by saving the file and running the analysis another
            // way, and "no definitions found" would send them looking for the wrong problem.
            if (script.length() > JsAnalyzer.MAX_INPUT_LENGTH) {
                String reason = String.format(
                        "%s is %.1f MiB, over the %d MiB scanning limit, so it was not analyzed.",
                        source,
                        script.length() / (1024.0 * 1024.0),
                        JsAnalyzer.MAX_INPUT_LENGTH / (1024 * 1024));
                panel.addFailedResult(source, reason);
                api.logging().logToOutput("gRPC-Web Coder: " + reason);
                return;
            }

            JsAnalyzer.Result result = JsAnalyzer.analyze(script);
            if (result.isEmpty()) {
                panel.addEmptyResult(source);
                api.logging().logToOutput(
                        "gRPC-Web Coder: no gRPC-Web definitions found in " + source + ".");
                return;
            }
            panel.addResult(source, result);
            api.logging().logToOutput(String.format(
                    "gRPC-Web Coder: %s produced %d endpoint(s) and %d field(s) in %d message(s).",
                    source,
                    result.endpoints().size(),
                    result.fieldCount(),
                    result.messages().size()));
        } catch (RuntimeException | StackOverflowError e) {
            // StackOverflowError is an Error, not a RuntimeException, and catching only the latter
            // made a blown stack vanish without a trace. Java's regex engine recurses as it
            // repeats, so a hostile or merely pathological script can overflow it; that has to
            // surface in the Errors tab and in the suite tab rather than silently doing nothing.
            //
            // Deliberately not Throwable: an OutOfMemoryError leaves the JVM in a state this
            // extension cannot reason about, and swallowing it would hide that from Burp.
            String reason = e.getClass().getSimpleName()
                    + (e.getMessage() == null ? "" : ": " + e.getMessage());
            panel.addFailedResult(source, "Analysis of " + source + " failed — " + reason);
            api.logging().logToError("gRPC-Web Coder failed to analyze " + source, e);
        }
    }

    private static ByteArray extractBody(HttpRequestResponse requestResponse) {
        if (requestResponse == null || !requestResponse.hasResponse()) {
            return null;
        }
        HttpResponse response = requestResponse.response();
        ByteArray body = response.body();
        return body == null || body.length() == 0 ? null : body;
    }

    /**
     * Decodes a response body to text, undoing Content-Encoding first.
     *
     * <p>Burp's own compression utilities are used rather than a hand-rolled inflater, so gzip,
     * deflate and brotli all work.
     *
     * <p>Called from the worker thread, never from the event dispatch thread: inflating a large
     * bundle is slow, and the encoding is passed in rather than re-read so that no
     * {@link HttpResponse} has to cross threads to get here.
     */
    private String decompress(String source, String encoding, ByteArray body) {
        CompressionType type = compressionType(encoding);
        if (type == null) {
            return new String(body.getBytes(), StandardCharsets.UTF_8);
        }
        try {
            ByteArray decompressed = api.utilities().compressionUtils().decompress(body, type);
            // Not every failure arrives as an exception: a body that is mislabelled or truncated
            // can come back empty instead of throwing, and treating that as a successful decode
            // meant scanning an empty string and reporting "no definitions found" with no hint
            // that decompression was the problem.
            if (decompressed == null || decompressed.length() == 0) {
                return fallBackToRawBytes(source, encoding, body, "it decompressed to nothing");
            }
            return new String(decompressed.getBytes(), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            return fallBackToRawBytes(source, encoding, body,
                    e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage());
        }
    }

    /**
     * Reports a failed decompression and scans the body as it arrived.
     *
     * <p>A mislabelled or truncated response is common enough that giving up on the file would be
     * worse than scanning the raw bytes, but it has to be said out loud: silently analyzing the
     * compressed bytes looks identical to a bundle that genuinely contains nothing.
     */
    private String fallBackToRawBytes(String source, String encoding, ByteArray body,
                                      String reason) {
        api.logging().logToOutput(
                "gRPC-Web Coder: could not decompress the " + encoding + " response from "
                        + source + " (" + reason + "), scanning it as-is.");
        return new String(body.getBytes(), StandardCharsets.UTF_8);
    }

    private static CompressionType compressionType(String encoding) {
        if (encoding == null) {
            return null;
        }
        String normalised = encoding.trim().toLowerCase(Locale.ROOT);
        if (normalised.contains("gzip")) {
            return CompressionType.GZIP;
        }
        if (normalised.contains("br")) {
            return CompressionType.BROTLI;
        }
        if (normalised.contains("deflate")) {
            return CompressionType.DEFLATE;
        }
        return null;
    }

    private static String header(HttpResponse response, String name) {
        try {
            return response.headerValue(name);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private static String describe(HttpRequestResponse requestResponse) {
        try {
            String url = requestResponse.request() == null
                    ? null
                    : requestResponse.request().url();
            if (url != null && !url.isBlank()) {
                // Trim a long query string so the table column stays readable.
                int query = url.indexOf('?');
                return query > 0 ? url.substring(0, query) : url;
            }
        } catch (RuntimeException e) {
            // A message with no service attached cannot produce a URL.
        }
        return "(unknown source)";
    }

    /** Stops the worker thread. Called from the extension's unloading handler. */
    void shutdown() {
        executor.shutdownNow();
        try {
            executor.awaitTermination(2, TimeUnit.SECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
