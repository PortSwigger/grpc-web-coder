package com.nxenon.grpcweb.codec;

/** Raised when a body is not a well-formed gRPC-Web frame stream. */
public class GrpcWebException extends Exception {

    private static final long serialVersionUID = 1L;

    public GrpcWebException(String message) {
        super(message);
    }

    public GrpcWebException(String message, Throwable cause) {
        super(message, cause);
    }
}
