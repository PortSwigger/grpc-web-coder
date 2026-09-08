package com.nxenon.grpcweb.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class Base64ChunksTest {

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("a single base64 blob decodes")
    void singleBlobDecodes() throws Exception {
        byte[] original = "the quick brown fox".getBytes(StandardCharsets.UTF_8);
        byte[] encoded = Base64.getEncoder().encode(original);
        assertArrayEquals(original, Base64Chunks.decode(encoded));
    }

    @Test
    @DisplayName("concatenated padded chunks all decode, not just the first")
    void concatenatedChunksDecode() throws Exception {
        // A streaming grpc-web-text response is a run of independently encoded, padded chunks.
        // Base64.getDecoder().decode() over the whole body stops at the first '=' and loses the
        // rest, which silently truncates every streaming response.
        byte[] first = "frame one".getBytes(StandardCharsets.UTF_8);
        byte[] second = "frame two".getBytes(StandardCharsets.UTF_8);
        byte[] third = "3".getBytes(StandardCharsets.UTF_8);

        String body = Base64.getEncoder().encodeToString(first)
                + Base64.getEncoder().encodeToString(second)
                + Base64.getEncoder().encodeToString(third);

        ByteArrayOutputStream expected = new ByteArrayOutputStream();
        expected.write(first);
        expected.write(second);
        expected.write(third);

        assertArrayEquals(expected.toByteArray(), Base64Chunks.decode(ascii(body)));
    }

    @Test
    @DisplayName("a chunk with two padding characters is split correctly")
    void doublePaddingIsHandled() throws Exception {
        // "a" encodes to "YQ==" (two padding chars); "bb" to "YmI=" (one).
        assertArrayEquals("abb".getBytes(StandardCharsets.UTF_8),
                Base64Chunks.decode(ascii("YQ==YmI=")));
    }

    @Test
    @DisplayName("line breaks and spaces inside the body are ignored")
    void whitespaceIsIgnored() throws Exception {
        byte[] original = "a longer payload that will wrap across lines".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().encodeToString(original);
        String wrapped = encoded.substring(0, 8) + "\r\n" + encoded.substring(8, 16)
                + "\n  " + encoded.substring(16);
        assertArrayEquals(original, Base64Chunks.decode(ascii(wrapped)));
    }

    @Test
    @DisplayName("an unpadded final chunk still decodes")
    void unpaddedInputDecodes() throws Exception {
        byte[] original = "abc".getBytes(StandardCharsets.UTF_8);
        String encoded = Base64.getEncoder().withoutPadding().encodeToString(original);
        assertArrayEquals(original, Base64Chunks.decode(ascii(encoded)));
    }

    @Test
    void emptyBodyDecodesToNothing() throws Exception {
        assertEquals(0, Base64Chunks.decode(new byte[0]).length);
    }

    @Test
    @DisplayName("a body that is not base64 at all is reported")
    void invalidBase64IsReported() {
        GrpcWebException e =
                assertThrows(GrpcWebException.class, () -> Base64Chunks.decode(ascii("!!!!")));
        assertEquals(true, e.getMessage().contains("not valid base64"));
    }

    @Test
    @DisplayName("encoding produces a single padded blob that decodes back")
    void encodeRoundTrips() throws Exception {
        byte[] original = new byte[256];
        for (int i = 0; i < 256; i++) {
            original[i] = (byte) i;
        }
        assertArrayEquals(original, Base64Chunks.decode(Base64Chunks.encode(original)));
    }
}
