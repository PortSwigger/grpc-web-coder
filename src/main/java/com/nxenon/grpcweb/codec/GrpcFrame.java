package com.nxenon.grpcweb.codec;

/**
 * A single gRPC-Web length-prefixed frame.
 *
 * <p>Every frame is a one-byte flag field followed by a big-endian 32-bit payload length and then
 * the payload itself. Bit 0 of the flags marks a compressed payload and bit 7 marks the trailer
 * frame that closes a gRPC-Web response.
 *
 * @see <a href="https://github.com/grpc/grpc/blob/master/doc/PROTOCOL-WEB.md">PROTOCOL-WEB.md</a>
 */
public final class GrpcFrame {

    /** Bit 0: the payload is compressed with the message encoding named in grpc-encoding. */
    public static final int FLAG_COMPRESSED = 0x01;

    /** Bit 7: this frame carries trailers rather than a message. */
    public static final int FLAG_TRAILER = 0x80;

    /** Size of the flags byte plus the 32-bit length. */
    public static final int HEADER_LENGTH = 5;

    private final int flags;
    private final byte[] payload;

    public GrpcFrame(int flags, byte[] payload) {
        this.flags = flags & 0xFF;
        this.payload = payload.clone();
    }

    public static GrpcFrame data(byte[] payload) {
        return new GrpcFrame(0, payload);
    }

    public static GrpcFrame compressedData(byte[] payload) {
        return new GrpcFrame(FLAG_COMPRESSED, payload);
    }

    public static GrpcFrame trailer(byte[] payload) {
        return new GrpcFrame(FLAG_TRAILER, payload);
    }

    public int flags() {
        return flags;
    }

    /** The raw frame payload, still compressed if {@link #isCompressed()}. */
    public byte[] payload() {
        return payload.clone();
    }

    public int payloadLength() {
        return payload.length;
    }

    public boolean isCompressed() {
        return (flags & FLAG_COMPRESSED) != 0;
    }

    public boolean isTrailer() {
        return (flags & FLAG_TRAILER) != 0;
    }

    /** A frame carrying a protobuf message, as opposed to trailers. */
    public boolean isData() {
        return !isTrailer();
    }

    /** Returns a copy of this frame with a different payload, preserving the flags. */
    public GrpcFrame withPayload(byte[] newPayload) {
        return new GrpcFrame(flags, newPayload);
    }
}
