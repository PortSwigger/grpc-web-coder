package com.nxenon.grpcweb.codec;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Base64 handling for {@code application/grpc-web-text} bodies.
 *
 * <p>A unary gRPC-Web-text body is a single base64 blob, but a <em>streaming</em> body is a
 * concatenation of independently base64-encoded chunks, each with its own padding. A plain
 * {@code Base64.getDecoder().decode()} over the whole body stops at the first {@code =} and loses
 * everything after it, so the body is split on padding boundaries and each chunk decoded on its own.
 */
public final class Base64Chunks {

    private Base64Chunks() {
    }

    /**
     * Decodes a body that may be one base64 blob or several concatenated padded blobs.
     *
     * @throws GrpcWebException if any chunk is not valid base64
     */
    public static byte[] decode(byte[] body) throws GrpcWebException {
        String text = new String(body, StandardCharsets.US_ASCII);
        ByteArrayOutputStream decoded = new ByteArrayOutputStream();
        StringBuilder chunk = new StringBuilder();
        boolean inPadding = false;

        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c)) {
                continue;
            }
            if (c == '=') {
                chunk.append(c);
                inPadding = true;
                continue;
            }
            if (inPadding) {
                // A base64 character after padding starts a new chunk.
                decodeChunk(chunk.toString(), decoded);
                chunk.setLength(0);
                inPadding = false;
            }
            chunk.append(c);
        }
        decodeChunk(chunk.toString(), decoded);
        return decoded.toByteArray();
    }

    private static void decodeChunk(String chunk, ByteArrayOutputStream sink)
            throws GrpcWebException {
        if (chunk.isEmpty()) {
            return;
        }
        try {
            // The strict decoder, not the MIME one: getMimeDecoder() silently discards characters
            // outside the base64 alphabet, so a body that is not base64 at all would decode to
            // nothing and be reported as "no frames" instead of as a bad body. Whitespace is
            // already stripped above, which is the only leniency actually needed here.
            sink.write(Base64.getDecoder().decode(chunk));
        } catch (IllegalArgumentException e) {
            throw new GrpcWebException("Body is not valid base64: " + e.getMessage(), e);
        } catch (java.io.IOException e) {
            // ByteArrayOutputStream does not throw, but write() declares it.
            throw new GrpcWebException("Failed to assemble decoded body", e);
        }
    }

    /** Encodes a frame stream as a single padded base64 blob, which every gRPC-Web server accepts. */
    public static byte[] encode(byte[] frames) {
        return Base64.getEncoder().encode(frames);
    }
}
