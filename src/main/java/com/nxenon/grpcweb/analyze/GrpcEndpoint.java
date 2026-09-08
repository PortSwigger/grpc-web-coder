package com.nxenon.grpcweb.analyze;

import java.util.Objects;

/**
 * A gRPC method discovered in JavaScript, e.g. {@code /auth.AuthService/Login}.
 *
 * <p>These are the routes a gRPC-Web client can call, including ones no UI element reaches, which is
 * what makes them worth extracting during a test.
 *
 * <p>A generated {@code MethodDescriptor} also names the method's call style and its request and
 * response message types. Those are recovered when present, because they are what turn a list of
 * paths into a usable {@code .proto} reconstruction; a path found any other way carries the path
 * alone.
 */
public final class GrpcEndpoint implements Comparable<GrpcEndpoint> {

    /** How the method is called. */
    public enum CallStyle {
        UNARY("unary"),
        SERVER_STREAMING("server streaming"),
        CLIENT_STREAMING("client streaming"),
        BIDI_STREAMING("bidirectional streaming"),
        UNKNOWN("?");

        private final String label;

        CallStyle(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Whether the response is a stream, which a {@code .proto} marks with {@code stream}. */
        public boolean isResponseStreaming() {
            return this == SERVER_STREAMING || this == BIDI_STREAMING;
        }

        /** Whether the request is a stream. */
        public boolean isRequestStreaming() {
            return this == CLIENT_STREAMING || this == BIDI_STREAMING;
        }

        /** Resolves a {@code grpc.web.MethodType} constant name. */
        static CallStyle fromMethodType(String methodType) {
            if (methodType == null) {
                return UNKNOWN;
            }
            return switch (methodType.toUpperCase(java.util.Locale.ROOT)) {
                case "UNARY" -> UNARY;
                case "SERVER_STREAMING" -> SERVER_STREAMING;
                case "CLIENT_STREAMING" -> CLIENT_STREAMING;
                case "BIDI_STREAMING", "BIDIRECTIONAL_STREAMING" -> BIDI_STREAMING;
                default -> UNKNOWN;
            };
        }
    }

    private final String path;
    private final String service;
    private final String method;
    private final CallStyle callStyle;
    private final String requestType;
    private final String responseType;

    GrpcEndpoint(String path, String service, String method) {
        this(path, service, method, CallStyle.UNKNOWN, null, null);
    }

    GrpcEndpoint(String path, String service, String method, CallStyle callStyle,
                 String requestType, String responseType) {
        this.path = path;
        this.service = service;
        this.method = method;
        this.callStyle = callStyle == null ? CallStyle.UNKNOWN : callStyle;
        this.requestType = requestType;
        this.responseType = responseType;
    }

    /** The full path as it appears on the wire, always starting with {@code /}. */
    public String path() {
        return path;
    }

    /** The fully-qualified service name, e.g. {@code auth.AuthService}. */
    public String service() {
        return service;
    }

    /** The method name, e.g. {@code Login}. */
    public String method() {
        return method;
    }

    public CallStyle callStyle() {
        return callStyle;
    }

    /** The fully-qualified request message, or empty when it was not recovered. */
    public String requestType() {
        return requestType == null ? "" : requestType;
    }

    /** The fully-qualified response message, or empty when it was not recovered. */
    public String responseType() {
        return responseType == null ? "" : responseType;
    }

    /** Whether this endpoint carries its message types, and can appear in a {@code .proto} rpc. */
    public boolean hasMessageTypes() {
        return !requestType().isEmpty() && !responseType().isEmpty();
    }

    /**
     * Merges in detail from another record of the same path.
     *
     * <p>A path is often written twice — once in a {@code MethodDescriptor} with its types, and
     * again as a bare literal in the {@code rpcCall}. Whichever is seen second must not discard
     * what the first knew.
     */
    GrpcEndpoint mergedWith(GrpcEndpoint other) {
        if (other == null || !path.equals(other.path)) {
            return this;
        }
        return new GrpcEndpoint(
                path,
                service,
                method,
                callStyle != CallStyle.UNKNOWN ? callStyle : other.callStyle,
                hasMessageTypes() ? requestType : other.requestType,
                hasMessageTypes() ? responseType : other.responseType);
    }

    @Override
    public int compareTo(GrpcEndpoint other) {
        return path.compareTo(other.path);
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof GrpcEndpoint endpoint && path.equals(endpoint.path);
    }

    @Override
    public int hashCode() {
        return Objects.hash(path);
    }

    @Override
    public String toString() {
        return path;
    }
}
