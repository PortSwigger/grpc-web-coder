package com.nxenon.grpcweb.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.requests.HttpRequest;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpRequestEditor;
import com.nxenon.grpcweb.codec.GrpcWebFormat;

import java.awt.Component;

/**
 * The "Decoded gRPC-Web" tab on a request.
 *
 * <p>{@link #getRequest()} is called when the message is forwarded or sent. It returns the original
 * request untouched unless the body both decoded and re-encoded cleanly, so a payload the extension
 * did not fully understand is never silently rewritten.
 */
public final class GrpcWebRequestEditor implements ExtensionProvidedHttpRequestEditor {

    private final GrpcWebEditor editor;

    private HttpRequest request;
    private GrpcWebFormat format;

    public GrpcWebRequestEditor(MontoyaApi api, ExtensionSettings settings,
                                EditorCreationContext context) {
        this.editor = new GrpcWebEditor(api, settings, context.editorMode() != EditorMode.READ_ONLY);
    }

    @Override
    public boolean isEnabledFor(HttpRequestResponse requestResponse) {
        if (requestResponse == null || requestResponse.request() == null) {
            return false;
        }
        return editor.formatFor(requestResponse.request()) != null;
    }

    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        request = requestResponse == null ? null : requestResponse.request();
        if (request == null) {
            format = null;
            editor.setBody(null, null);
            return;
        }
        editor.adoptEditedTypeDefinition();
        format = editor.formatFor(request);
        editor.setBody(request.body(), format);
    }

    @Override
    public HttpRequest getRequest() {
        if (request == null) {
            return null;
        }
        ByteArray body = editor.encodedBody();
        if (body == null) {
            return request;
        }
        // withBody updates Content-Length, which the Jython version did not: it rebuilt the
        // message from the original header list and left a stale length behind.
        return request.withBody(body);
    }

    @Override
    public String caption() {
        return "Decoded gRPC-Web";
    }

    @Override
    public Component uiComponent() {
        return editor.uiComponent();
    }

    @Override
    public Selection selectedData() {
        return editor.selectedData();
    }

    @Override
    public boolean isModified() {
        return editor.isModified();
    }
}
