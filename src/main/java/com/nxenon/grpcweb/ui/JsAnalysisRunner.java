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
        // Copy what is needed now: holding HttpRequestResponse objects across threads and across
        // time is what the "large projects" criterion warns against.
        for (HttpRequestResponse requestResponse : selected) {
            String source = describe(requestResponse);
            ByteArray body = extractBody(requestResponse);
            if (body == null) {
                api.logging().logToOutput(
                        "gRPC-Web Coder: " + source + " has no response body to analyze.");
                continue;
            }
            String script = decompress(requestResponse.response(), body);
            executor.execute(() -> run(source, script));
        }
    }

    private void run(String source, String script) {
        try {
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
        } catch (RuntimeException e) {
            // The script is untrusted content; a failure must not kill the worker thread.
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
     */
    private String decompress(HttpResponse response, ByteArray body) {
        String encoding = header(response, "Content-Encoding");
        CompressionType type = compressionType(encoding);
        if (type == null) {
            return new String(body.getBytes(), StandardCharsets.UTF_8);
        }
        try {
            ByteArray decompressed = api.utilities().compressionUtils().decompress(body, type);
            return new String(decompressed.getBytes(), StandardCharsets.UTF_8);
        } catch (RuntimeException e) {
            // A mislabelled or truncated body is common; fall back to the raw bytes rather than
            // giving up on the file entirely.
            api.logging().logToOutput(
                    "gRPC-Web Coder: could not decompress a " + encoding
                            + " response, scanning it as-is.");
            return new String(body.getBytes(), StandardCharsets.UTF_8);
        }
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
