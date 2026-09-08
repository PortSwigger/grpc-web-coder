package com.nxenon.grpcweb.codec;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * Reads and writes gRPC-Web frame streams.
 *
 * <p>A body is a sequence of frames, each a flags byte, a big-endian 32-bit length, and that many
 * payload bytes. Requests normally hold one data frame; responses hold one or more data frames
 * followed by a trailer frame.
 *
 * <p>The length is read as a 32-bit big-endian integer <em>after</em> the flags byte. Folding the
 * flags byte into the length — as happens if all five header bytes are read as one number — happens
 * to work only while the flags byte is zero, and breaks on the first compressed or trailer frame.
 */
public final class GrpcWebCodec {

    /**
     * Refuse to allocate for a frame larger than this. A frame header is attacker-controlled, so a
     * claimed length of 4 GiB must not become a 4 GiB allocation inside Burp.
     */
    private static final long MAX_FRAME_LENGTH = 64L * 1024 * 1024;

    private GrpcWebCodec() {
    }

    // ------------------------------------------------------------- body level

    /**
     * Decodes a message body into frames, undoing the base64 layer for
     * {@link GrpcWebFormat#TEXT}.
     *
     * @throws GrpcWebException if the body is truncated, or not valid base64 for the text format
     */
    public static List<GrpcFrame> readBody(byte[] body, GrpcWebFormat format)
            throws GrpcWebException {
        byte[] frames = format == GrpcWebFormat.TEXT ? Base64Chunks.decode(body) : body;
        return readFrames(frames);
    }

    /** Encodes frames into a message body, applying base64 for {@link GrpcWebFormat#TEXT}. */
    public static byte[] writeBody(List<GrpcFrame> frames, GrpcWebFormat format) {
        byte[] encoded = writeFrames(frames);
        return format == GrpcWebFormat.TEXT ? Base64Chunks.encode(encoded) : encoded;
    }

    // ------------------------------------------------------------ frame level

    /**
     * Parses a frame stream.
     *
     * @throws GrpcWebException if a header is incomplete, a payload is truncated, or a frame claims
     *                          an implausible length
     */
    public static List<GrpcFrame> readFrames(byte[] data) throws GrpcWebException {
        List<GrpcFrame> frames = new ArrayList<>();
        int offset = 0;

        while (offset < data.length) {
            int remaining = data.length - offset;
            if (remaining < GrpcFrame.HEADER_LENGTH) {
                throw new GrpcWebException("Truncated frame header: " + remaining
                        + " byte(s) left at offset " + offset + ", need " + GrpcFrame.HEADER_LENGTH);
            }

            int flags = data[offset] & 0xFF;
            long length = ((long) (data[offset + 1] & 0xFF) << 24)
                    | ((long) (data[offset + 2] & 0xFF) << 16)
                    | ((long) (data[offset + 3] & 0xFF) << 8)
                    | (data[offset + 4] & 0xFF);
            offset += GrpcFrame.HEADER_LENGTH;

            if (length > MAX_FRAME_LENGTH) {
                throw new GrpcWebException("Frame claims a length of " + length
                        + " bytes, which exceeds the " + MAX_FRAME_LENGTH + " byte limit");
            }
            if (offset + length > data.length) {
                throw new GrpcWebException("Frame at offset " + (offset - GrpcFrame.HEADER_LENGTH)
                        + " claims " + length + " payload byte(s) but only "
                        + (data.length - offset) + " remain");
            }

            byte[] payload = new byte[(int) length];
            System.arraycopy(data, offset, payload, 0, (int) length);
            offset += (int) length;
            frames.add(new GrpcFrame(flags, payload));
        }
        return frames;
    }

    /** Serialises frames back to a byte stream. */
    public static byte[] writeFrames(List<GrpcFrame> frames) {
        int total = 0;
        for (GrpcFrame frame : frames) {
            total += GrpcFrame.HEADER_LENGTH + frame.payloadLength();
        }
        byte[] data = new byte[total];
        int offset = 0;
        for (GrpcFrame frame : frames) {
            byte[] payload = frame.payload();
            data[offset] = (byte) frame.flags();
            data[offset + 1] = (byte) (payload.length >>> 24);
            data[offset + 2] = (byte) (payload.length >>> 16);
            data[offset + 3] = (byte) (payload.length >>> 8);
            data[offset + 4] = (byte) payload.length;
            offset += GrpcFrame.HEADER_LENGTH;
            System.arraycopy(payload, 0, data, offset, payload.length);
            offset += payload.length;
        }
        return data;
    }

    // ------------------------------------------------------------ compression

    /**
     * Returns the frame's payload, inflating it if the compressed flag is set.
     *
     * @throws GrpcWebException if a frame is flagged compressed but is not valid gzip
     */
    public static byte[] decompress(GrpcFrame frame) throws GrpcWebException {
        if (!frame.isCompressed()) {
            return frame.payload();
        }
        try (GZIPInputStream gzip = new GZIPInputStream(new ByteArrayInputStream(frame.payload()))) {
            return gzip.readAllBytes();
        } catch (IOException e) {
            throw new GrpcWebException(
                    "Frame is flagged as compressed but could not be gunzipped: " + e.getMessage(), e);
        }
    }

    /** Gzips a payload, for re-encoding a frame that arrived compressed. */
    public static byte[] compress(byte[] payload) throws GrpcWebException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(buffer)) {
            gzip.write(payload);
        } catch (IOException e) {
            throw new GrpcWebException("Failed to gzip frame payload: " + e.getMessage(), e);
        }
        return buffer.toByteArray();
    }
}
