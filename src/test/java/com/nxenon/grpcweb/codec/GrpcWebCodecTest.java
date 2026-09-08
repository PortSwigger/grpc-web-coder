package com.nxenon.grpcweb.codec;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GrpcWebCodecTest {

    private static byte[] bytes(int... values) {
        byte[] result = new byte[values.length];
        for (int i = 0; i < values.length; i++) {
            result[i] = (byte) values[i];
        }
        return result;
    }

    @Nested
    class FrameHeaders {

        @Test
        @DisplayName("a single data frame is read with its flags and length separated")
        void singleDataFrame() throws Exception {
            // flags 0x00, length 3, payload 08 96 01
            byte[] data = bytes(0x00, 0x00, 0x00, 0x00, 0x03, 0x08, 0x96, 0x01);
            List<GrpcFrame> frames = GrpcWebCodec.readFrames(data);

            assertEquals(1, frames.size());
            GrpcFrame frame = frames.get(0);
            assertEquals(0, frame.flags());
            assertTrue(frame.isData());
            assertFalse(frame.isTrailer());
            assertFalse(frame.isCompressed());
            assertArrayEquals(bytes(0x08, 0x96, 0x01), frame.payload());
        }

        @Test
        @DisplayName("the flags byte is not folded into the length")
        void flagsByteIsNotPartOfTheLength() throws Exception {
            // A trailer frame: flags 0x80, length 13. Reading all five header bytes as one
            // big-endian integer yields 0x8000000D = 2147483661, a nonsense length. The flags byte
            // must be read on its own and the length taken from the following four bytes.
            byte[] trailerText = "grpc-status:0".getBytes(StandardCharsets.US_ASCII);
            assertEquals(13, trailerText.length);

            byte[] data = new byte[GrpcFrame.HEADER_LENGTH + trailerText.length];
            data[0] = (byte) 0x80;
            data[4] = (byte) trailerText.length;
            System.arraycopy(trailerText, 0, data, GrpcFrame.HEADER_LENGTH, trailerText.length);

            List<GrpcFrame> frames = GrpcWebCodec.readFrames(data);
            assertEquals(1, frames.size());
            assertTrue(frames.get(0).isTrailer());
            assertEquals(13, frames.get(0).payloadLength());
            assertEquals("grpc-status:0",
                    new String(frames.get(0).payload(), StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("the compressed flag is read without disturbing the length")
        void compressedFlagIsReadSeparately() throws Exception {
            byte[] data = bytes(0x01, 0x00, 0x00, 0x00, 0x02, 0xAA, 0xBB);
            List<GrpcFrame> frames = GrpcWebCodec.readFrames(data);

            assertTrue(frames.get(0).isCompressed());
            assertFalse(frames.get(0).isTrailer());
            assertArrayEquals(bytes(0xAA, 0xBB), frames.get(0).payload());
        }

        @Test
        @DisplayName("a four-byte length above 16 MiB is read as big-endian")
        void multiByteLengthIsBigEndian() throws Exception {
            // length 0x00010000 = 65536
            byte[] payload = new byte[65536];
            byte[] data = new byte[GrpcFrame.HEADER_LENGTH + payload.length];
            data[0] = 0x00;
            data[1] = 0x00;
            data[2] = 0x01;
            data[3] = 0x00;
            data[4] = 0x00;
            System.arraycopy(payload, 0, data, GrpcFrame.HEADER_LENGTH, payload.length);

            List<GrpcFrame> frames = GrpcWebCodec.readFrames(data);
            assertEquals(65536, frames.get(0).payloadLength());
        }

        @Test
        void emptyBodyHasNoFrames() throws Exception {
            assertTrue(GrpcWebCodec.readFrames(new byte[0]).isEmpty());
        }

        @Test
        @DisplayName("a zero-length payload is a valid frame")
        void zeroLengthPayloadIsValid() throws Exception {
            List<GrpcFrame> frames =
                    GrpcWebCodec.readFrames(bytes(0x00, 0x00, 0x00, 0x00, 0x00));
            assertEquals(1, frames.size());
            assertEquals(0, frames.get(0).payloadLength());
        }
    }

    @Nested
    class MultipleFrames {

        @Test
        @DisplayName("every frame in a streaming body is read, not just the first")
        void allFramesAreRead() throws Exception {
            // Two data frames then a trailer frame.
            byte[] data = bytes(
                    0x00, 0x00, 0x00, 0x00, 0x02, 0x08, 0x01,
                    0x00, 0x00, 0x00, 0x00, 0x02, 0x08, 0x02,
                    0x80, 0x00, 0x00, 0x00, 0x0F,
                    'g', 'r', 'p', 'c', '-', 's', 't', 'a', 't', 'u', 's', ':', ' ', '0', '\n');

            List<GrpcFrame> frames = GrpcWebCodec.readFrames(data);
            assertEquals(3, frames.size());
            assertTrue(frames.get(0).isData());
            assertTrue(frames.get(1).isData());
            assertTrue(frames.get(2).isTrailer());
            assertArrayEquals(bytes(0x08, 0x01), frames.get(0).payload());
            assertArrayEquals(bytes(0x08, 0x02), frames.get(1).payload());
        }

        @Test
        @DisplayName("frames survive a write/read round-trip")
        void framesRoundTrip() throws Exception {
            List<GrpcFrame> original = List.of(
                    GrpcFrame.data(bytes(0x08, 0x01)),
                    GrpcFrame.data(new byte[0]),
                    GrpcFrame.compressedData(bytes(0xDE, 0xAD)),
                    GrpcFrame.trailer("grpc-status:0\r\n".getBytes(StandardCharsets.US_ASCII)));

            byte[] encoded = GrpcWebCodec.writeFrames(original);
            List<GrpcFrame> decoded = GrpcWebCodec.readFrames(encoded);

            assertEquals(original.size(), decoded.size());
            for (int i = 0; i < original.size(); i++) {
                assertEquals(original.get(i).flags(), decoded.get(i).flags(), "frame " + i);
                assertArrayEquals(original.get(i).payload(), decoded.get(i).payload(), "frame " + i);
            }
            assertArrayEquals(encoded, GrpcWebCodec.writeFrames(decoded));
        }
    }

    @Nested
    class MalformedBodies {

        @Test
        @DisplayName("a header shorter than five bytes is reported, not silently ignored")
        void truncatedHeaderIsRejected() {
            GrpcWebException e = assertThrows(GrpcWebException.class,
                    () -> GrpcWebCodec.readFrames(bytes(0x00, 0x00, 0x00)));
            assertTrue(e.getMessage().contains("Truncated frame header"), e.getMessage());
        }

        @Test
        @DisplayName("a payload shorter than its declared length is reported")
        void truncatedPayloadIsRejected() {
            GrpcWebException e = assertThrows(GrpcWebException.class,
                    () -> GrpcWebCodec.readFrames(bytes(0x00, 0x00, 0x00, 0x00, 0x10, 0x01, 0x02)));
            assertTrue(e.getMessage().contains("but only"), e.getMessage());
        }

        @Test
        @DisplayName("an implausible declared length is refused instead of allocating for it")
        void oversizedLengthIsRefused() {
            // Claims 0xFFFFFFFF bytes. A naive reader would try to allocate 4 GiB inside Burp.
            GrpcWebException e = assertThrows(GrpcWebException.class,
                    () -> GrpcWebCodec.readFrames(bytes(0x00, 0xFF, 0xFF, 0xFF, 0xFF)));
            assertTrue(e.getMessage().contains("exceeds"), e.getMessage());
        }

        @Test
        @DisplayName("trailing bytes after the last complete frame are reported")
        void trailingPartialFrameIsRejected() {
            byte[] data = bytes(0x00, 0x00, 0x00, 0x00, 0x01, 0x08, 0x00, 0x00);
            assertThrows(GrpcWebException.class, () -> GrpcWebCodec.readFrames(data));
        }
    }

    @Nested
    class Compression {

        @Test
        @DisplayName("an uncompressed frame's payload is returned unchanged")
        void uncompressedPayloadPassesThrough() throws Exception {
            GrpcFrame frame = GrpcFrame.data(bytes(0x08, 0x01));
            assertArrayEquals(bytes(0x08, 0x01), GrpcWebCodec.decompress(frame));
        }

        @Test
        @DisplayName("a gzip frame inflates, and the result deflates back to the same bytes")
        void gzipRoundTrips() throws Exception {
            byte[] payload = "a repeated payload a repeated payload".getBytes(StandardCharsets.UTF_8);
            byte[] compressed = GrpcWebCodec.compress(payload);

            GrpcFrame frame = GrpcFrame.compressedData(compressed);
            assertTrue(frame.isCompressed());
            assertArrayEquals(payload, GrpcWebCodec.decompress(frame));
        }

        @Test
        @DisplayName("a frame flagged compressed but holding garbage is reported clearly")
        void invalidGzipIsReported() {
            GrpcFrame frame = GrpcFrame.compressedData(bytes(0x01, 0x02, 0x03));
            GrpcWebException e =
                    assertThrows(GrpcWebException.class, () -> GrpcWebCodec.decompress(frame));
            assertTrue(e.getMessage().contains("gunzipped"), e.getMessage());
        }
    }

    @Nested
    class BodyLevel {

        @Test
        @DisplayName("a grpc-web-text body is base64-decoded before framing")
        void textBodyIsBase64Decoded() throws Exception {
            byte[] frames = bytes(0x00, 0x00, 0x00, 0x00, 0x03, 0x08, 0x96, 0x01);
            byte[] body = java.util.Base64.getEncoder().encode(frames);

            List<GrpcFrame> read = GrpcWebCodec.readBody(body, GrpcWebFormat.TEXT);
            assertEquals(1, read.size());
            assertArrayEquals(bytes(0x08, 0x96, 0x01), read.get(0).payload());
        }

        @Test
        @DisplayName("a grpc-web+proto body is framed directly")
        void protoBodyIsUsedAsIs() throws Exception {
            byte[] frames = bytes(0x00, 0x00, 0x00, 0x00, 0x03, 0x08, 0x96, 0x01);
            List<GrpcFrame> read = GrpcWebCodec.readBody(frames, GrpcWebFormat.PROTO);
            assertArrayEquals(bytes(0x08, 0x96, 0x01), read.get(0).payload());
        }

        @Test
        @DisplayName("writing a body re-applies base64 only for the text format")
        void writeBodyMatchesFormat() {
            List<GrpcFrame> frames = List.of(GrpcFrame.data(bytes(0x08, 0x96, 0x01)));

            byte[] proto = GrpcWebCodec.writeBody(frames, GrpcWebFormat.PROTO);
            assertArrayEquals(bytes(0x00, 0x00, 0x00, 0x00, 0x03, 0x08, 0x96, 0x01), proto);

            byte[] text = GrpcWebCodec.writeBody(frames, GrpcWebFormat.TEXT);
            assertArrayEquals(java.util.Base64.getEncoder().encode(proto), text);
        }

        @Test
        @DisplayName("a text body survives a decode/encode round-trip")
        void textBodyRoundTrips() throws Exception {
            byte[] frames = bytes(0x00, 0x00, 0x00, 0x00, 0x03, 0x08, 0x96, 0x01);
            byte[] body = java.util.Base64.getEncoder().encode(frames);

            List<GrpcFrame> read = GrpcWebCodec.readBody(body, GrpcWebFormat.TEXT);
            assertArrayEquals(body, GrpcWebCodec.writeBody(read, GrpcWebFormat.TEXT));
        }
    }
}
