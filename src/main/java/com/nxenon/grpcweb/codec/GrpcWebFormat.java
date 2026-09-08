package com.nxenon.grpcweb.codec;

import java.util.Locale;

/**
 * The two gRPC-Web body encodings this extension handles.
 *
 * <p>{@link #TEXT} bodies are base64 over the frame stream; {@link #PROTO} bodies are the frame
 * stream as raw bytes. Both carry the same protobuf messages underneath.
 */
public enum GrpcWebFormat {

    /** {@code application/grpc-web-text}: base64-encoded frames. */
    TEXT("application/grpc-web-text"),

    /** {@code application/grpc-web+proto}: binary frames. */
    PROTO("application/grpc-web+proto");

    private final String canonicalContentType;

    GrpcWebFormat(String canonicalContentType) {
        this.canonicalContentType = canonicalContentType;
    }

    public String canonicalContentType() {
        return canonicalContentType;
    }

    /**
     * Identifies the format from a Content-Type header value.
     *
     * <p>Parameters such as {@code ; charset=utf-8} are ignored, matching is case-insensitive, and
     * the {@code +proto} suffix variants are accepted. {@code application/grpc-web-text} is checked
     * before {@code application/grpc-web} so the text form is not swallowed by the binary prefix.
     *
     * @param contentType a header value, which may be {@code null}
     * @return the matching format, or {@code null} if the value is not a gRPC-Web content type
     */
    public static GrpcWebFormat fromContentType(String contentType) {
        if (contentType == null) {
            return null;
        }
        int parameterStart = contentType.indexOf(';');
        String mediaType = (parameterStart >= 0 ? contentType.substring(0, parameterStart) : contentType)
                .trim()
                .toLowerCase(Locale.ROOT);

        if (mediaType.equals("application/grpc-web-text")
                || mediaType.equals("application/grpc-web-text+proto")) {
            return TEXT;
        }
        if (mediaType.equals("application/grpc-web")
                || mediaType.equals("application/grpc-web+proto")) {
            return PROTO;
        }
        return null;
    }
}
