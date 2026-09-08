package com.nxenon.grpcweb.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.editor.extension.EditorMode;
import com.nxenon.grpcweb.ui.BurpTestHarness.RecordingEditor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Drives the request and response editor tabs the way Burp does, against real Montoya interfaces.
 *
 * <p>These cover the boundary the codec tests cannot reach: whether the tab appears for a given
 * message, what {@code getRequest()} hands back after an edit, and — most importantly — that a
 * message nobody touched is returned unchanged.
 */
class EditorIntegrationTest {

    /** Unary grpc-web-text body holding {@code { 1: "amin", 2: 1337 }}. */
    private static final String UNARY_BODY = "AAAAAAkKBGFtaW4QuQo=";

    private static final String TEXT = "application/grpc-web-text";
    private static final String PROTO = "application/grpc-web+proto";

    private Map<String, RecordingEditor> editors;
    private MontoyaApi api;
    private ExtensionSettings settings;

    @BeforeEach
    void setUp() {
        editors = BurpTestHarness.editorMap();
        api = BurpTestHarness.api(editors);
        settings = new ExtensionSettings(new FakePreferences());
    }

    /** The payload editor is the first one the editor creates. */
    private RecordingEditor payloadEditor() {
        return editors.get("editor0");
    }

    /** The type definition editor is the second. */
    private RecordingEditor typeDefinitionEditor() {
        return editors.get("editor1");
    }

    private GrpcWebRequestEditor requestEditor() {
        return new GrpcWebRequestEditor(api, settings,
                BurpTestHarness.creationContext(EditorMode.DEFAULT));
    }

    @Nested
    class TabVisibility {

        @Test
        @DisplayName("the tab appears on a grpc-web-text request")
        void appearsForTextRequests() {
            assertTrue(requestEditor().isEnabledFor(
                    BurpTestHarness.requestWith(TEXT, UNARY_BODY)));
        }

        @Test
        @DisplayName("the tab appears on a grpc-web+proto request")
        void appearsForProtoRequests() {
            assertTrue(requestEditor().isEnabledFor(
                    BurpTestHarness.requestWith(PROTO, "anything")));
        }

        @Test
        @DisplayName("the tab stays hidden on an ordinary JSON request")
        void hiddenForOtherRequests() {
            assertFalse(requestEditor().isEnabledFor(
                    BurpTestHarness.requestWith("application/json", "{}")));
        }

        @Test
        @DisplayName("the tab appears on any request once it is pinned on in settings")
        void appearsForAnythingWhenPinned() {
            settings.setShowTabAlways(true);
            assertTrue(requestEditor().isEnabledFor(
                    BurpTestHarness.requestWith("application/json", "{}")));
        }

        @Test
        @DisplayName("a null request response does not make the tab appear or throw")
        void nullMessageIsSafe() {
            assertFalse(requestEditor().isEnabledFor(null));
        }

        @Test
        @DisplayName("the response tab is hidden while response decoding is switched off")
        void responseTabRespectsTheSetting() {
            GrpcWebResponseEditor editor = new GrpcWebResponseEditor(api, settings,
                    BurpTestHarness.creationContext(EditorMode.DEFAULT));
            HttpRequestResponse message = BurpTestHarness.responseWith(TEXT, UNARY_BODY);

            assertTrue(editor.isEnabledFor(message));

            settings.setDecodeResponses(false);
            assertFalse(editor.isEnabledFor(message));
        }

        @Test
        @DisplayName("the response tab is hidden on a request that has no response yet")
        void responseTabHiddenWithoutAResponse() {
            GrpcWebResponseEditor editor = new GrpcWebResponseEditor(api, settings,
                    BurpTestHarness.creationContext(EditorMode.DEFAULT));
            assertFalse(editor.isEnabledFor(BurpTestHarness.requestWith(TEXT, UNARY_BODY)));
        }
    }

    @Nested
    class Decoding {

        @Test
        @DisplayName("selecting a message fills both tabs")
        void bothTabsArePopulated() {
            GrpcWebRequestEditor editor = requestEditor();
            editor.setRequestResponse(BurpTestHarness.requestWith(TEXT, UNARY_BODY));

            assertTrue(payloadEditor().text().contains("\"amin\""), payloadEditor().text());
            assertTrue(payloadEditor().text().contains("1337"));
            assertTrue(typeDefinitionEditor().text().contains("string"));
        }

        @Test
        @DisplayName("a body that will not decode shows the reason instead of throwing")
        void undecodableBodyShowsAMessage() {
            GrpcWebRequestEditor editor = requestEditor();
            editor.setRequestResponse(BurpTestHarness.requestWith(TEXT, "!!!!not base64!!!!"));

            assertTrue(payloadEditor().text().contains("Could not decode"),
                    payloadEditor().text());
        }

        @Test
        @DisplayName("an empty body is reported as empty, not as an error")
        void emptyBodyIsReported() {
            GrpcWebRequestEditor editor = requestEditor();
            editor.setRequestResponse(BurpTestHarness.requestWith(TEXT, ""));
            assertEquals("", payloadEditor().text());
        }

        @Test
        @DisplayName("a response body decodes in the response tab")
        void responseBodyDecodes() {
            GrpcWebResponseEditor editor = new GrpcWebResponseEditor(api, settings,
                    BurpTestHarness.creationContext(EditorMode.DEFAULT));
            editor.setRequestResponse(BurpTestHarness.responseWith(TEXT, UNARY_BODY));

            assertTrue(payloadEditor().text().contains("\"amin\""), payloadEditor().text());
        }
    }

    @Nested
    class ReEncoding {

        @Test
        @DisplayName("a message nobody edited is returned byte-for-byte unchanged")
        void untouchedMessageIsUnchanged() {
            GrpcWebRequestEditor editor = requestEditor();
            HttpRequestResponse message = BurpTestHarness.requestWith(TEXT, UNARY_BODY);
            editor.setRequestResponse(message);

            HttpRequest result = editor.getRequest();
            assertEquals(UNARY_BODY,
                    new String(result.body().getBytes(), StandardCharsets.UTF_8));
        }

        @Test
        @DisplayName("an edit in the payload tab reaches the outgoing body")
        void editReachesTheBody() {
            GrpcWebRequestEditor editor = requestEditor();
            editor.setRequestResponse(BurpTestHarness.requestWith(TEXT, UNARY_BODY));

            payloadEditor().type(payloadEditor().text().replace("amin", "root"));

            HttpRequest result = editor.getRequest();
            String newBody = new String(result.body().getBytes(), StandardCharsets.UTF_8);
            assertFalse(newBody.equals(UNARY_BODY), "the body should have changed");

            // Decoding the new body back must show the edit.
            GrpcWebRequestEditor verifier = new GrpcWebRequestEditor(api, settings,
                    BurpTestHarness.creationContext(EditorMode.DEFAULT));
            verifier.setRequestResponse(BurpTestHarness.requestWith(TEXT, newBody));
            assertTrue(editors.get("editor2").text().contains("root"),
                    editors.get("editor2").text());
        }

        @Test
        @DisplayName("a payload edited into invalid JSON leaves the original message alone")
        void invalidEditIsNotApplied() {
            GrpcWebRequestEditor editor = requestEditor();
            HttpRequestResponse message = BurpTestHarness.requestWith(TEXT, UNARY_BODY);
            editor.setRequestResponse(message);

            payloadEditor().type("{ this is not json");

            // The original request object is handed back untouched rather than a broken body.
            assertSame(message.request(), editor.getRequest());
        }

        @Test
        @DisplayName("a body that never decoded is never rewritten")
        void undecodableBodyIsNotRewritten() {
            GrpcWebRequestEditor editor = requestEditor();
            HttpRequestResponse message =
                    BurpTestHarness.requestWith(TEXT, "!!!!not base64!!!!");
            editor.setRequestResponse(message);

            assertSame(message.request(), editor.getRequest());
        }

        @Test
        @DisplayName("a read-only editor never rewrites the message")
        void readOnlyEditorNeverRewrites() {
            GrpcWebRequestEditor editor = new GrpcWebRequestEditor(api, settings,
                    BurpTestHarness.creationContext(EditorMode.READ_ONLY));
            HttpRequestResponse message = BurpTestHarness.requestWith(TEXT, UNARY_BODY);
            editor.setRequestResponse(message);

            payloadEditor().type("{\"1\": \"tampered\"}");
            assertSame(message.request(), editor.getRequest());
        }

        @Test
        @DisplayName("an edited response body reaches the outgoing response")
        void responseEditReachesTheBody() {
            GrpcWebResponseEditor editor = new GrpcWebResponseEditor(api, settings,
                    BurpTestHarness.creationContext(EditorMode.DEFAULT));
            editor.setRequestResponse(BurpTestHarness.responseWith(TEXT, UNARY_BODY));

            payloadEditor().type(payloadEditor().text().replace("amin", "root"));

            HttpResponse result = editor.getResponse();
            assertFalse(new String(result.body().getBytes(), StandardCharsets.UTF_8)
                    .equals(UNARY_BODY));
        }

        @Test
        @DisplayName("getRequest before any message is selected returns null rather than throwing")
        void noMessageYieldsNull() {
            assertEquals(null, requestEditor().getRequest());
        }
    }

    @Nested
    class TypeDefinitionOverride {

        @Test
        @DisplayName("the type definition tab starts read-only")
        void startsReadOnly() {
            requestEditor();
            assertFalse(typeDefinitionEditor().isEditable());
        }

        @Test
        @DisplayName("an edited type definition is picked up when the message is forwarded")
        void editedDefinitionIsAdoptedOnForward() {
            GrpcWebRequestEditor editor = requestEditor();
            editor.setRequestResponse(BurpTestHarness.requestWith(TEXT, UNARY_BODY));

            // Field 2 is a varint guessed as int; call it a bool instead.
            typeDefinitionEditor().type(
                    "{\"1\": {\"type\": \"string\"}, \"2\": {\"type\": \"bool\"}}");

            // Reselecting the message applies it, as does forwarding.
            editor.setRequestResponse(BurpTestHarness.requestWith(TEXT, UNARY_BODY));
            assertTrue(payloadEditor().text().contains("true"), payloadEditor().text());
        }

        @Test
        @DisplayName("an unparseable type definition does not break the tab")
        void brokenDefinitionIsIgnored() {
            GrpcWebRequestEditor editor = requestEditor();
            editor.setRequestResponse(BurpTestHarness.requestWith(TEXT, UNARY_BODY));

            typeDefinitionEditor().type("{ not json at all");
            editor.setRequestResponse(BurpTestHarness.requestWith(TEXT, UNARY_BODY));

            // The payload still decodes with the detected definition.
            assertTrue(payloadEditor().text().contains("amin"), payloadEditor().text());
        }
    }

    @Nested
    class TabMetadata {

        @Test
        @DisplayName("both tabs are captioned and have a component")
        void captionsAndComponents() {
            GrpcWebRequestEditor request = requestEditor();
            assertEquals("Decoded gRPC-Web", request.caption());
            assertTrue(request.uiComponent() != null);

            GrpcWebResponseEditor response = new GrpcWebResponseEditor(api, settings,
                    BurpTestHarness.creationContext(EditorMode.DEFAULT));
            assertEquals("Decoded gRPC-Web", response.caption());
            assertTrue(response.uiComponent() != null);
        }

        @Test
        @DisplayName("isModified reflects an edit in either tab")
        void isModifiedTracksEdits() {
            GrpcWebRequestEditor editor = requestEditor();
            editor.setRequestResponse(BurpTestHarness.requestWith(TEXT, UNARY_BODY));
            assertFalse(editor.isModified());

            payloadEditor().type("{\"1\": \"x\"}");
            assertTrue(editor.isModified());
        }
    }

    @Nested
    class ForcedFormats {

        @Test
        @DisplayName("forcing grpc-web+proto decodes a binary body that has a text content type")
        void forcedProtoIsApplied() {
            settings.setDetectionMode(ExtensionSettings.DetectionMode.FORCE_PROTO);

            // Raw frame bytes, latin-1 safe: flags 0, length 2, field 1 varint 1.
            String rawFrames = new String(
                    new byte[]{0, 0, 0, 0, 2, 0x08, 0x01}, StandardCharsets.ISO_8859_1);

            GrpcWebRequestEditor editor = requestEditor();
            editor.setRequestResponse(BurpTestHarness.requestWith("application/json", rawFrames));

            assertTrue(payloadEditor().text().contains("\"1\""), payloadEditor().text());
        }
    }

    @Nested
    class ContextMenu {

        @Test
        @DisplayName("the menu offers one item for a selection with a response")
        void offersAnItemForASelection() {
            AnalyzerPanel panel = new AnalyzerPanel(api);
            JsAnalysisRunner runner = new JsAnalysisRunner(api, panel);
            GrpcWebContextMenu menu = new GrpcWebContextMenu(runner);

            // Built before the stubbing call: creating mocks inside an in-progress when(...)
            // is what Mockito reports as unfinished stubbing.
            List<HttpRequestResponse> selection = List.of(
                    BurpTestHarness.responseWith("application/javascript", "var x = 1;"));

            var event = org.mockito.Mockito.mock(
                    burp.api.montoya.ui.contextmenu.ContextMenuEvent.class);
            org.mockito.Mockito.when(event.selectedRequestResponses()).thenReturn(selection);

            assertEquals(1, menu.provideMenuItems(event).size());
            runner.shutdown();
        }

        @Test
        @DisplayName("a selection whose entries have no response offers nothing")
        void ignoresSelectionsWithoutResponses() {
            AnalyzerPanel panel = new AnalyzerPanel(api);
            JsAnalysisRunner runner = new JsAnalysisRunner(api, panel);
            GrpcWebContextMenu menu = new GrpcWebContextMenu(runner);

            List<HttpRequestResponse> selection =
                    List.of(BurpTestHarness.requestWith(TEXT, UNARY_BODY));

            var event = org.mockito.Mockito.mock(
                    burp.api.montoya.ui.contextmenu.ContextMenuEvent.class);
            org.mockito.Mockito.when(event.selectedRequestResponses()).thenReturn(selection);
            org.mockito.Mockito.when(event.messageEditorRequestResponse())
                    .thenReturn(java.util.Optional.empty());

            assertTrue(menu.provideMenuItems(event).isEmpty());
            runner.shutdown();
        }
    }
}
