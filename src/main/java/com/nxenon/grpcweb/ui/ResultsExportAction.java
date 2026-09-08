package com.nxenon.grpcweb.ui;

import burp.api.montoya.MontoyaApi;
import com.nxenon.grpcweb.export.AnalysisExporter;
import com.nxenon.grpcweb.export.AnalysisResults;

import javax.swing.JFileChooser;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.Frame;
import java.awt.Toolkit;
import java.awt.datatransfer.StringSelection;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Saves analysis results to a file, or puts them on the clipboard.
 *
 * <p>Two BApp Store rules shape this class. Dialogs are parented to Burp's own frame via
 * {@code SwingUtils.suiteFrame()}, so they behave correctly on multi-monitor setups instead of
 * appearing behind the main window. And the write itself runs on a worker thread: a save can land on
 * a network share or a slow volume, and blocking the event dispatch thread would freeze all of Burp.
 */
final class ResultsExportAction {

    private final MontoyaApi api;
    private final ExecutorService executor;

    ResultsExportAction(MontoyaApi api) {
        this.api = api;
        this.executor = Executors.newSingleThreadExecutor(runnable -> {
            Thread thread = new Thread(runnable, "grpc-web-coder-export");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Asks for a destination and writes the results there.
     *
     * @param status called on the event dispatch thread with a message to show the user
     */
    void saveToFile(AnalysisResults results, AnalysisExporter.Format format,
                    Consumer<String> status) {

        if (results.isEmpty()) {
            status.accept("Nothing to export yet. Analyze a JavaScript response first.");
            return;
        }

        // Rendering happens before the dialog so that a failure is reported without the user
        // first picking a filename, and so the worker thread never touches the results model.
        String content;
        try {
            content = AnalysisExporter.export(results, format);
        } catch (RuntimeException e) {
            status.accept("Could not render the export: " + e.getMessage());
            api.logging().logToError("gRPC-Web Coder export failed", e);
            return;
        }

        Frame parent = suiteFrame();
        JFileChooser chooser = new JFileChooser();
        chooser.setDialogTitle("Export gRPC-Web analysis results");
        chooser.setSelectedFile(new java.io.File(format.defaultFileName()));
        chooser.setFileFilter(new FileNameExtensionFilter(
                format.label() + " (*." + format.extension() + ")", format.extension()));

        if (chooser.showSaveDialog(parent) != JFileChooser.APPROVE_OPTION) {
            return;
        }
        Path destination = chooser.getSelectedFile().toPath();

        if (Files.exists(destination)) {
            int choice = JOptionPane.showConfirmDialog(parent,
                    destination.getFileName() + " already exists. Overwrite it?",
                    "Overwrite file?", JOptionPane.YES_NO_OPTION, JOptionPane.WARNING_MESSAGE);
            if (choice != JOptionPane.YES_OPTION) {
                return;
            }
        }

        executor.execute(() -> {
            try {
                Files.writeString(destination, content, StandardCharsets.UTF_8);
                String message = String.format("Exported %d endpoint(s) and %d field(s) to %s",
                        results.endpointCount(), results.fieldCount(), destination);
                api.logging().logToOutput("gRPC-Web Coder: " + message);
                SwingUtilities.invokeLater(() -> status.accept(message));
            } catch (IOException | RuntimeException e) {
                String reason = e.getMessage() == null ? e.toString() : e.getMessage();
                api.logging().logToError("gRPC-Web Coder could not write " + destination, e);
                SwingUtilities.invokeLater(() -> status.accept("Export failed: " + reason));
            }
        });
    }

    /** Puts the rendered results on the system clipboard. */
    void copyToClipboard(AnalysisResults results, AnalysisExporter.Format format,
                         Consumer<String> status) {

        if (results.isEmpty()) {
            status.accept("Nothing to copy yet. Analyze a JavaScript response first.");
            return;
        }
        try {
            String content = AnalysisExporter.export(results, format);
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(content), null);
            status.accept(String.format("Copied %s to the clipboard (%d characters).",
                    format.label(), content.length()));
        } catch (RuntimeException e) {
            String reason = e.getMessage() == null ? e.toString() : e.getMessage();
            status.accept("Copy failed: " + reason);
            api.logging().logToError("gRPC-Web Coder could not copy results", e);
        }
    }

    /**
     * Burp's main frame, for parenting dialogs.
     *
     * @return the suite frame, or {@code null} if it cannot be resolved, which
     *         {@link JFileChooser} treats as "no parent" rather than failing
     */
    private Frame suiteFrame() {
        try {
            return api.userInterface().swingUtils().suiteFrame();
        } catch (RuntimeException e) {
            return null;
        }
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
