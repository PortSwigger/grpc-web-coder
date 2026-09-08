package com.nxenon.grpcweb.ui;

import burp.api.montoya.MontoyaApi;
import burp.api.montoya.core.ByteArray;
import burp.api.montoya.http.message.HttpRequestResponse;
import burp.api.montoya.http.message.responses.HttpResponse;
import burp.api.montoya.ui.Selection;
import burp.api.montoya.ui.editor.extension.EditorCreationContext;
import burp.api.montoya.ui.editor.extension.EditorMode;
import burp.api.montoya.ui.editor.extension.ExtensionProvidedHttpResponseEditor;
import com.nxenon.grpcweb.codec.GrpcWebFormat;

import java.awt.Component;

/**
 * The "Decoded gRPC-Web" tab on a response.
 *
 * <p>The Jython version had no response support at all: it only ever called
 * {@code analyzeRequest}, so a server's reply stayed opaque. Responses are where server-streaming
 * bodies and trailer frames actually show up, which is why multi-frame handling matters here.
 */
public final class GrpcWebResponseEditor implements ExtensionProvidedHttpResponseEditor {

    private final ExtensionSettings settings;
    private final GrpcWebEditor editor;

    private HttpResponse response;

    public GrpcWebResponseEditor(MontoyaApi api, ExtensionSettings settings,
                                 EditorCreationContext context) {
        this.settings = settings;
        this.editor = new GrpcWebEditor(api, settings, context.editorMode() != EditorMode.READ_ONLY);
    }

    @Override
    public boolean isEnabledFor(HttpRequestResponse requestResponse) {
        if (!settings.decodeResponses()) {
            return false;
        }
        if (requestResponse == null || !requestResponse.hasResponse()) {
            return false;
        }
        return editor.formatFor(requestResponse.response()) != null;
    }

    @Override
    public void setRequestResponse(HttpRequestResponse requestResponse) {
        response = requestResponse == null || !requestResponse.hasResponse()
                ? null
                : requestResponse.response();
        if (response == null) {
            editor.setBody(null, null);
            return;
        }
        editor.adoptEditedTypeDefinition();
        GrpcWebFormat format = editor.formatFor(response);
        editor.setBody(response.body(), format);
    }

    @Override
    public HttpResponse getResponse() {
        if (response == null) {
            return null;
        }
        ByteArray body = editor.encodedBody();
        return body == null ? response : response.withBody(body);
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
