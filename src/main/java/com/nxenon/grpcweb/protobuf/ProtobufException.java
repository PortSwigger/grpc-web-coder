package com.nxenon.grpcweb.protobuf;

/**
 * Raised when a payload cannot be decoded as protobuf, or when a user-supplied type definition or
 * JSON document cannot be encoded back to the wire format.
 *
 * <p>This is a checked exception on purpose: every decode path in this extension handles untrusted
 * bytes from an HTTP message, and failures must surface as a message in the editor rather than a
 * stack trace on Burp's error stream.
 */
public class ProtobufException extends Exception {

    private static final long serialVersionUID = 1L;

    public ProtobufException(String message) {
        super(message);
    }

    public ProtobufException(String message, Throwable cause) {
        super(message, cause);
    }
}
