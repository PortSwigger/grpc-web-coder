package com.nxenon.grpcweb.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.NullSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

class GrpcWebFormatTest {

    @ParameterizedTest
    @DisplayName("gRPC-Web content types resolve to the right body encoding")
    @CsvSource({
            "application/grpc-web-text,TEXT",
            "application/grpc-web-text+proto,TEXT",
            "application/grpc-web+proto,PROTO",
            "application/grpc-web,PROTO",
    })
    void contentTypesResolve(String contentType, GrpcWebFormat expected) {
        assertSame(expected, GrpcWebFormat.fromContentType(contentType));
    }

    @ParameterizedTest
    @DisplayName("matching ignores case, surrounding space and parameters")
    @ValueSource(strings = {
            "APPLICATION/GRPC-WEB-TEXT",
            "  application/grpc-web-text  ",
            "application/grpc-web-text; charset=utf-8",
            "application/grpc-web-text;charset=UTF-8",
            "Application/gRPC-Web-Text",
    })
    void matchingIsLenient(String contentType) {
        assertSame(GrpcWebFormat.TEXT, GrpcWebFormat.fromContentType(contentType));
    }

    @Test
    @DisplayName("the text form is not swallowed by the shorter binary prefix")
    void textIsNotMistakenForProto() {
        // "application/grpc-web" is a prefix of "application/grpc-web-text", so a prefix match in
        // the wrong order would decode a base64 body as raw frames and produce garbage.
        assertSame(GrpcWebFormat.TEXT, GrpcWebFormat.fromContentType("application/grpc-web-text"));
        assertSame(GrpcWebFormat.PROTO, GrpcWebFormat.fromContentType("application/grpc-web"));
    }

    @ParameterizedTest
    @DisplayName("non-gRPC-Web content types are not claimed")
    @ValueSource(strings = {
            "application/json",
            "application/grpc",
            "application/x-protobuf",
            "text/plain",
            "application/grpc-web-textual",
            "",
    })
    void otherContentTypesAreIgnored(String contentType) {
        assertNull(GrpcWebFormat.fromContentType(contentType));
    }

    @ParameterizedTest
    @NullSource
    @DisplayName("a missing Content-Type header is handled without throwing")
    void nullContentTypeIsIgnored(String contentType) {
        assertNull(GrpcWebFormat.fromContentType(contentType));
    }
}
