package com.nxenon.grpcweb;

import burp.api.montoya.BurpExtension;
import burp.api.montoya.MontoyaApi;
import com.nxenon.grpcweb.ui.GrpcWebCoderUi;

/**
 * Entry point for the gRPC-Web Coder extension.
 *
 * <p>Encodes and decodes {@code application/grpc-web-text} and {@code application/grpc-web+proto}
 * bodies without needing the {@code .proto} schema, and pulls service routes and message fields out
 * of generated gRPC-Web JavaScript.
 *
 * <p>Everything is registered through the Montoya API, and every registration is released by the
 * unloading handler so the extension can be reloaded cleanly.
 */
public final class GrpcWebCoderExtension implements BurpExtension {

    private static final String NAME = "gRPC-Web Coder";

    @Override
    public void initialize(MontoyaApi api) {
        api.extension().setName(NAME);

        GrpcWebCoderUi ui = new GrpcWebCoderUi(api);
        ui.register();

        api.logging().logToOutput(NAME + " loaded.");
        api.logging().logToOutput(
                "Decodes application/grpc-web-text and application/grpc-web+proto bodies in the"
                        + " message editor. Right-click a JavaScript response and choose"
                        + " \"Analyze gRPC-Web Endpoints\" to map a target's services.");
    }
}
