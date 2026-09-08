package com.nxenon.grpcweb.protobuf;

/**
 * Protobuf wire types, as encoded in the low three bits of a field tag.
 *
 * @see <a href="https://protobuf.dev/programming-guides/encoding/">Protocol Buffers encoding</a>
 */
public enum WireType {
    VARINT(0),
    FIXED64(1),
    LENGTH_DELIMITED(2),
    START_GROUP(3),
    END_GROUP(4),
    FIXED32(5);

    private final int value;

    WireType(int value) {
        this.value = value;
    }

    public int value() {
        return value;
    }

    /**
     * Resolves the wire type encoded in a tag's low three bits.
     *
     * @throws ProtobufException for wire types 6 and 7, which are not defined by the spec
     */
    public static WireType fromTag(int tag) throws ProtobufException {
        int wireValue = tag & 0x07;
        for (WireType type : values()) {
            if (type.value == wireValue) {
                return type;
            }
        }
        throw new ProtobufException("Reserved/invalid wire type " + wireValue);
    }
}
