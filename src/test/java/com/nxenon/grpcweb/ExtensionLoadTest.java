package com.nxenon.grpcweb;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import burp.api.montoya.extension.ExtensionUnloadingHandler;
import burp.api.montoya.ui.contextmenu.ContextMenuItemsProvider;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;
import burp.api.montoya.ui.editor.extension.HttpRequestEditorProvider;
import burp.api.montoya.ui.editor.extension.HttpResponseEditorProvider;
import burp.api.montoya.ui.editor.EditorOptions;
import burp.api.montoya.ui.editor.RawEditor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import javax.swing.JPanel;
import javax.swing.SwingUtilities;
import java.awt.Component;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.RETURNS_DEEP_STUBS;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

/**
 * Checks the extension loads and wires itself up against a stand-in Burp.
 *
 * <p>A BApp that throws during {@code initialize} is dead on arrival, and that failure is invisible
 * to unit tests of the codec. This exercises the real entry point: registering the editors, the
 * suite tab, the context menu and — per the BApp Store criteria — an unloading handler.
 */
class ExtensionLoadTest {

    /** Runs any queued Swing work, since the UI is built inside invokeLater. */
    private static void flushEventQueue() throws Exception {
        SwingUtilities.invokeAndWait(() -> { });
    }

    /**
     * A stand-in Burp.
     *
     * <p>Deep stubs cover most of the API surface, but {@code createRawEditor} must return an
     * editor whose {@code uiComponent()} is a real Swing component: a mocked {@code Component}
     * cannot be added to a container, because Swing needs its tree lock.
     */
    private static MontoyaApi burpApi() {
        MontoyaApi api = mock(MontoyaApi.class, RETURNS_DEEP_STUBS);
        RawEditor rawEditor = mock(RawEditor.class);
        org.mockito.Mockito.when(rawEditor.uiComponent()).thenReturn(new JPanel());
        org.mockito.Mockito.when(api.userInterface().createRawEditor(any(EditorOptions[].class)))
                .thenReturn(rawEditor);
        return api;
    }

    @Test
    @DisplayName("the entry point implements Burp's extension interface")
    void entryPointImplementsBurpExtension() {
        assertTrue(BurpExtension.class.isAssignableFrom(GrpcWebCoderExtension.class));
    }

    @Test
    @DisplayName("initialize names the extension and registers every provider")
    void initializeRegistersEverything() throws Exception {
        MontoyaApi api = burpApi();

        new GrpcWebCoderExtension().initialize(api);
        flushEventQueue();

        verify(api.extension()).setName("gRPC-Web Coder");
        verify(api.userInterface()).registerSuiteTab(eq("gRPC-Web Coder"), any(Component.class));
        verify(api.userInterface()).registerHttpRequestEditorProvider(any());
        verify(api.userInterface()).registerHttpResponseEditorProvider(any());
        verify(api.userInterface()).registerContextMenuItemsProvider(any());
    }

    @Test
    @DisplayName("an unloading handler is registered, as the BApp Store criteria require")
    void unloadingHandlerIsRegistered() throws Exception {
        MontoyaApi api = burpApi();

        new GrpcWebCoderExtension().initialize(api);
        flushEventQueue();

        ArgumentCaptor<ExtensionUnloadingHandler> handler =
                ArgumentCaptor.forClass(ExtensionUnloadingHandler.class);
        verify(api.extension()).registerUnloadingHandler(handler.capture());
        assertNotNull(handler.getValue());

        // Unloading must complete without throwing, so a reload during a test is clean.
        handler.getValue().extensionUnloaded();
    }

    @Test
    @DisplayName("the registered providers build usable editors in both editable and read-only mode")
    void providersBuildEditors() throws Exception {
        MontoyaApi api = burpApi();

        new GrpcWebCoderExtension().initialize(api);
        flushEventQueue();

        ArgumentCaptor<HttpRequestEditorProvider> requestProvider =
                ArgumentCaptor.forClass(HttpRequestEditorProvider.class);
        verify(api.userInterface()).registerHttpRequestEditorProvider(requestProvider.capture());

        ArgumentCaptor<HttpResponseEditorProvider> responseProvider =
                ArgumentCaptor.forClass(HttpResponseEditorProvider.class);
        verify(api.userInterface()).registerHttpResponseEditorProvider(responseProvider.capture());

        for (EditorMode mode : EditorMode.values()) {
            EditorCreationContext context = mock(EditorCreationContext.class, RETURNS_DEEP_STUBS);
            org.mockito.Mockito.when(context.editorMode()).thenReturn(mode);

            var requestEditor = requestProvider.getValue().provideHttpRequestEditor(context);
            assertNotNull(requestEditor, "request editor for " + mode);
            assertNotNull(requestEditor.caption());
            assertNotNull(requestEditor.uiComponent());

            var responseEditor = responseProvider.getValue().provideHttpResponseEditor(context);
            assertNotNull(responseEditor, "response editor for " + mode);
            assertNotNull(responseEditor.caption());
            assertNotNull(responseEditor.uiComponent());
        }
    }

    @Test
    @DisplayName("the context menu offers nothing when nothing with a response is selected")
    void contextMenuIsEmptyWithoutASelection() throws Exception {
        MontoyaApi api = burpApi();

        new GrpcWebCoderExtension().initialize(api);
        flushEventQueue();

        ArgumentCaptor<ContextMenuItemsProvider> menuProvider =
                ArgumentCaptor.forClass(ContextMenuItemsProvider.class);
        verify(api.userInterface()).registerContextMenuItemsProvider(menuProvider.capture());

        var event = mock(burp.api.montoya.ui.contextmenu.ContextMenuEvent.class);
        org.mockito.Mockito.when(event.selectedRequestResponses())
                .thenReturn(java.util.List.of());
        org.mockito.Mockito.when(event.messageEditorRequestResponse())
                .thenReturn(java.util.Optional.empty());

        assertTrue(menuProvider.getValue().provideMenuItems(event).isEmpty());
    }
}
