package com.nxenon.grpcweb.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.utilities.CompressionType;
import com.nxenon.grpcweb.analyze.JsAnalyzer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Threading guarantees for the JavaScript analyzer.
 *
 * <p>{@code analyze} is called from a context-menu action listener, which Swing runs on the event
 * dispatch thread. Everything expensive it touches — inflating a compressed bundle, then scanning
 * it — has to happen elsewhere, or Burp's whole UI freezes for the duration. The BApp Store
 * acceptance criteria call this out, and PortSwigger's automated reviewer caught an earlier version
 * of this class inflating the body on the calling thread before queueing the scan.
 */
class JsAnalysisRunnerTest {

    private static final String SCRIPT =
            "var d=new grpc.web.MethodDescriptor('/auth.AuthService/Login',"
            + "grpc.web.MethodType.UNARY,Req,Res);";

    private static final String WORKER_THREAD_PREFIX = "grpc-web-coder-analyzer";

    /** Collects the runner's output log, counting down once a run has reported its result. */
    private static final class LogWatcher {
        private final List<String> messages = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch finished = new CountDownLatch(1);

        void attach(MontoyaApi api) {
            // Resolve the deep stub before stubbing it: calling api.logging() inside the
            // when(...) chain creates a mock mid-stubbing, which Mockito rejects.
            burp.api.montoya.logging.Logging logging = api.logging();
            doAnswer(invocation -> {
                String message = invocation.getArgument(0);
                messages.add(message);
                if (message.contains("endpoint(s)") || message.contains("no gRPC-Web definitions")) {
                    finished.countDown();
                }
                return null;
            }).when(logging).logToOutput(anyString());
        }

        boolean awaitRun() throws InterruptedException {
            return finished.await(20, TimeUnit.SECONDS);
        }

        String summary() {
            synchronized (messages) {
                return messages.stream()
                        .filter(m -> m.contains("endpoint(s)"))
                        .findFirst()
                        .orElse("(no summary logged; saw " + messages + ")");
            }
        }
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("decompression runs on the worker thread, not the caller's")
    void decompressionDoesNotRunOnTheCallingThread() throws Exception {
        CountDownLatch decompressStarted = new CountDownLatch(1);
        CountDownLatch releaseDecompress = new CountDownLatch(1);
        AtomicReference<String> decompressThread = new AtomicReference<>();

        MontoyaApi api = BurpTestHarness.api(BurpTestHarness.editorMap());
        when(api.utilities().compressionUtils().decompress(any(ByteArray.class), any()))
                .thenAnswer(invocation -> {
                    decompressThread.set(Thread.currentThread().getName());
                    decompressStarted.countDown();
                    // Stands in for a slow inflate of a large bundle. If this runs on the caller's
                    // thread, analyze() cannot return until it is released.
                    try {
                        releaseDecompress.await(30, TimeUnit.SECONDS);
                    } catch (InterruptedException interrupted) {
                        // shutdown() interrupts the worker; returning quietly keeps the test's
                        // output clean rather than printing a stack trace from a dying thread.
                        Thread.currentThread().interrupt();
                    }
                    return new BurpTestHarness.TestByteArray(
                            SCRIPT.getBytes(StandardCharsets.UTF_8));
                });

        JsAnalysisRunner runner = new JsAnalysisRunner(api, new AnalyzerPanel(api));
        HttpRequestResponse gzipped = BurpTestHarness.encodedResponseWith(
                "application/javascript", "gzip", new byte[]{1, 2, 3, 4});

        long startedAt = System.nanoTime();
        runner.analyze(List.of(gzipped));
        long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;

        assertTrue(decompressStarted.await(30, TimeUnit.SECONDS),
                "decompression should have begun on the worker thread");

        // The decisive check: whoever inflated the body, it was not this thread.
        assertNotEquals(Thread.currentThread().getName(), decompressThread.get());
        assertTrue(decompressThread.get().startsWith(WORKER_THREAD_PREFIX),
                "expected the analyzer worker, but decompression ran on " + decompressThread.get());

        // And analyze() returned while decompression was still blocked.
        assertTrue(elapsedMillis < 10_000,
                "analyze() blocked for " + elapsedMillis + "ms waiting on decompression");

        releaseDecompress.countDown();
        runner.shutdown();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a gzip body is inflated and its endpoints are reported")
    void gzipBodyIsDecompressedAndAnalyzed() throws Exception {
        MontoyaApi api = BurpTestHarness.api(BurpTestHarness.editorMap());
        when(api.utilities().compressionUtils().decompress(any(ByteArray.class), any()))
                .thenAnswer(invocation -> {
                    assertEquals(CompressionType.GZIP, invocation.getArgument(1));
                    return new BurpTestHarness.TestByteArray(
                            SCRIPT.getBytes(StandardCharsets.UTF_8));
                });

        LogWatcher log = new LogWatcher();
        log.attach(api);
        JsAnalysisRunner runner = new JsAnalysisRunner(api, new AnalyzerPanel(api));

        runner.analyze(List.of(BurpTestHarness.encodedResponseWith(
                "application/javascript", "gzip", new byte[]{1, 2, 3, 4})));

        assertTrue(log.awaitRun(), "the analysis never completed");
        assertTrue(log.summary().contains("1 endpoint(s)"), log.summary());
        assertTrue(log.summary().contains("https://example.test/app.js"), log.summary());
        runner.shutdown();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("an uncompressed body never reaches the decompressor")
    void plainBodySkipsDecompression() throws Exception {
        MontoyaApi api = BurpTestHarness.api(BurpTestHarness.editorMap());
        LogWatcher log = new LogWatcher();
        log.attach(api);
        JsAnalysisRunner runner = new JsAnalysisRunner(api, new AnalyzerPanel(api));

        runner.analyze(List.of(BurpTestHarness.encodedResponseWith(
                "application/javascript", null, SCRIPT.getBytes(StandardCharsets.UTF_8))));

        assertTrue(log.awaitRun(), "the analysis never completed");
        assertTrue(log.summary().contains("1 endpoint(s)"), log.summary());
        burp.api.montoya.utilities.CompressionUtils compressionUtils =
                api.utilities().compressionUtils();
        verify(compressionUtils, never()).decompress(any(ByteArray.class), any());
        runner.shutdown();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a body that will not inflate is scanned as-is rather than dropped")
    void undecompressibleBodyFallsBackToRawBytes() throws Exception {
        MontoyaApi api = BurpTestHarness.api(BurpTestHarness.editorMap());
        when(api.utilities().compressionUtils().decompress(any(ByteArray.class), any()))
                .thenThrow(new RuntimeException("not actually gzip"));

        LogWatcher log = new LogWatcher();
        log.attach(api);
        JsAnalysisRunner runner = new JsAnalysisRunner(api, new AnalyzerPanel(api));

        // Labelled gzip but really plain text: a mislabelled response must still be scanned.
        runner.analyze(List.of(BurpTestHarness.encodedResponseWith(
                "application/javascript", "gzip", SCRIPT.getBytes(StandardCharsets.UTF_8))));

        assertTrue(log.awaitRun(), "the analysis never completed");
        assertTrue(log.summary().contains("1 endpoint(s)"), log.summary());
        runner.shutdown();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a StackOverflowError is reported rather than vanishing")
    void stackOverflowIsReported() throws Exception {
        MontoyaApi api = BurpTestHarness.api(BurpTestHarness.editorMap());
        when(api.utilities().compressionUtils().decompress(any(ByteArray.class), any()))
                .thenThrow(new StackOverflowError());

        // StackOverflowError is an Error, not a RuntimeException. Catching only the latter meant a
        // blown stack in the regex engine produced no output at all, not even in the Errors tab.
        burp.api.montoya.logging.Logging logging = api.logging();
        CountDownLatch errorLogged = new CountDownLatch(1);
        doAnswer(invocation -> {
            errorLogged.countDown();
            return null;
        }).when(logging).logToError(anyString(), any(Throwable.class));

        JsAnalysisRunner runner = new JsAnalysisRunner(api, new AnalyzerPanel(api));
        runner.analyze(List.of(BurpTestHarness.encodedResponseWith(
                "application/javascript", "gzip", new byte[]{1, 2, 3, 4})));

        assertTrue(errorLogged.await(20, TimeUnit.SECONDS),
                "a StackOverflowError should reach the Errors tab");
        runner.shutdown();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a StackOverflowError does not kill the worker thread")
    void workerSurvivesAStackOverflow() throws Exception {
        MontoyaApi api = BurpTestHarness.api(BurpTestHarness.editorMap());
        AtomicReference<Boolean> first = new AtomicReference<>(Boolean.TRUE);
        when(api.utilities().compressionUtils().decompress(any(ByteArray.class), any()))
                .thenAnswer(invocation -> {
                    if (first.getAndSet(Boolean.FALSE)) {
                        throw new StackOverflowError();
                    }
                    return new BurpTestHarness.TestByteArray(
                            SCRIPT.getBytes(StandardCharsets.UTF_8));
                });

        LogWatcher log = new LogWatcher();
        log.attach(api);
        JsAnalysisRunner runner = new JsAnalysisRunner(api, new AnalyzerPanel(api));

        // The first run blows the stack; the second must still be processed.
        runner.analyze(List.of(
                BurpTestHarness.encodedResponseWith("application/javascript", "gzip",
                        new byte[]{1, 2, 3, 4}),
                BurpTestHarness.encodedResponseWith("application/javascript", "gzip",
                        new byte[]{5, 6, 7, 8})));

        assertTrue(log.awaitRun(), "the second analysis never completed");
        assertTrue(log.summary().contains("1 endpoint(s)"), log.summary());
        runner.shutdown();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("an oversized script says so, rather than \"no definitions found\"")
    void oversizedScriptIsReportedBySize() throws Exception {
        MontoyaApi api = BurpTestHarness.api(BurpTestHarness.editorMap());
        byte[] huge = new byte[JsAnalyzer.MAX_INPUT_LENGTH + 1024];
        java.util.Arrays.fill(huge, (byte) 'a');
        when(api.utilities().compressionUtils().decompress(any(ByteArray.class), any()))
                .thenReturn(new BurpTestHarness.TestByteArray(huge));

        LogWatcher log = new LogWatcher();
        burp.api.montoya.logging.Logging logging = api.logging();
        CountDownLatch reported = new CountDownLatch(1);
        AtomicReference<String> message = new AtomicReference<>("");
        doAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text.contains("scanning limit")) {
                message.set(text);
                reported.countDown();
            }
            return null;
        }).when(logging).logToOutput(anyString());

        JsAnalysisRunner runner = new JsAnalysisRunner(api, new AnalyzerPanel(api));
        runner.analyze(List.of(BurpTestHarness.encodedResponseWith(
                "application/javascript", "gzip", new byte[]{1, 2, 3, 4})));

        assertTrue(reported.await(20, TimeUnit.SECONDS),
                "an oversized bundle should say it was too big");
        assertTrue(message.get().contains("MiB"), message.get());
        assertFalse(message.get().contains("no gRPC-Web definitions"), message.get());
        runner.shutdown();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("a body that decompresses to nothing is reported, not silently scanned")
    void emptyDecompressionIsReported() throws Exception {
        MontoyaApi api = BurpTestHarness.api(BurpTestHarness.editorMap());
        // Burp's decompressor does not always throw on a mislabelled body; it can hand back an
        // empty result, which used to look exactly like a bundle containing nothing.
        when(api.utilities().compressionUtils().decompress(any(ByteArray.class), any()))
                .thenReturn(new BurpTestHarness.TestByteArray(new byte[0]));

        burp.api.montoya.logging.Logging logging = api.logging();
        CountDownLatch reported = new CountDownLatch(1);
        doAnswer(invocation -> {
            String text = invocation.getArgument(0);
            if (text.contains("could not decompress")) {
                reported.countDown();
            }
            return null;
        }).when(logging).logToOutput(anyString());

        JsAnalysisRunner runner = new JsAnalysisRunner(api, new AnalyzerPanel(api));
        runner.analyze(List.of(BurpTestHarness.encodedResponseWith(
                "application/javascript", "gzip", SCRIPT.getBytes(StandardCharsets.UTF_8))));

        assertTrue(reported.await(20, TimeUnit.SECONDS),
                "a body that decompressed to nothing should be reported");
        runner.shutdown();
    }

    @Test
    @Timeout(value = 60, unit = TimeUnit.SECONDS)
    @DisplayName("an empty selection is reported and queues nothing")
    void emptySelectionIsHandled() {
        MontoyaApi api = BurpTestHarness.api(BurpTestHarness.editorMap());
        JsAnalysisRunner runner = new JsAnalysisRunner(api, new AnalyzerPanel(api));

        runner.analyze(List.of());
        runner.analyze(null);

        burp.api.montoya.logging.Logging logging = api.logging();
        verify(logging, org.mockito.Mockito.times(2))
                .logToOutput("gRPC-Web Coder: nothing selected to analyze.");
        runner.shutdown();
    }
}
