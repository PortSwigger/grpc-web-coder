package com.nxenon.grpcweb.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.Registration;

import javax.swing.SwingUtilities;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Wires the extension's UI into Burp and takes it back out again on unload.
 *
 * <p>Every {@link Registration} is kept so it can be deregistered, and the analyzer's worker thread
 * is stopped, when the extension unloads. Without that, reloading the extension during a test would
 * leave duplicate editor tabs and an orphaned thread behind.
 */
public final class GrpcWebCoderUi {

    private final MontoyaApi api;

    // Populated on the event dispatch thread but read by the unloading handler, which Burp calls
    // on its own thread, so both need to be safe to hand between threads.
    private final List<Registration> registrations = new CopyOnWriteArrayList<>();

    private volatile JsAnalysisRunner analysisRunner;
    private volatile AnalyzerPanel analyzerPanel;

    public GrpcWebCoderUi(MontoyaApi api) {
        this.api = api;
    }

    /** Builds the UI on the event dispatch thread and registers it with Burp. */
    public void register() {
        ExtensionSettings settings = new ExtensionSettings(api.persistence().preferences());

        // Swing components must be created on the event dispatch thread. initialize() is not
        // guaranteed to run there, so the UI is built inside invokeLater and the registrations
        // that depend on it are made from the same block.
        SwingUtilities.invokeLater(() -> {
            AnalyzerPanel panel = new AnalyzerPanel(api);
            analyzerPanel = panel;
            analysisRunner = new JsAnalysisRunner(api, panel);
            SettingsPanel settingsPanel = new SettingsPanel(settings, panel);

            api.userInterface().applyThemeToComponent(settingsPanel.uiComponent());

            registrations.add(api.userInterface().registerSuiteTab(
                    "gRPC-Web Coder", settingsPanel.uiComponent()));
            registrations.add(api.userInterface().registerHttpRequestEditorProvider(
                    context -> new GrpcWebRequestEditor(api, settings, context)));
            registrations.add(api.userInterface().registerHttpResponseEditorProvider(
                    context -> new GrpcWebResponseEditor(api, settings, context)));
            registrations.add(api.userInterface().registerContextMenuItemsProvider(
                    new GrpcWebContextMenu(analysisRunner)));
        });

        api.extension().registerUnloadingHandler(this::unload);
    }

    private void unload() {
        for (Registration registration : registrations) {
            if (registration != null && registration.isRegistered()) {
                registration.deregister();
            }
        }
        registrations.clear();
        if (analysisRunner != null) {
            analysisRunner.shutdown();
            analysisRunner = null;
        }
        if (analyzerPanel != null) {
            analyzerPanel.shutdown();
            analyzerPanel = null;
        }
        api.logging().logToOutput("gRPC-Web Coder unloaded.");
    }
}
