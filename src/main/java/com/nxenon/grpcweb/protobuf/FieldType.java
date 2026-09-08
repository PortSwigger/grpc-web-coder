package com.nxenon.grpcweb.protobuf;

import java.util.Collections;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;

/**
 * The concrete types a protobuf field can be interpreted as.
 *
 * <p>The protobuf wire format only carries a wire type, not a field type: a varint on the wire
 * could be an {@code int32}, a {@code bool}, or a zig-zag encoded {@code sint64}, and there is no
 * way to tell them apart without the {@code .proto} definition. Each constant here therefore pairs
 * a human-editable label (what appears in the Type Definition tab) with the wire type it must be
 * encoded as.
 *
 * <h2>Packed types</h2>
 * proto3 packs {@code repeated} scalar fields by default, encoding the whole run as one
 * length-delimited field rather than one field per element. On the wire that is indistinguishable
 * from a {@code bytes} field, so the {@code packed_*} types are never guessed — they are offered as
 * alternatives and take effect when the user selects one in the Type Definition tab.
 */
public enum FieldType {
    INT("int", WireType.VARINT),
    UINT("uint", WireType.VARINT),
    SINT("sint", WireType.VARINT),
    BOOL("bool", WireType.VARINT),

    FIXED32("fixed32", WireType.FIXED32),
    SFIXED32("sfixed32", WireType.FIXED32),
    FLOAT("float", WireType.FIXED32),

    FIXED64("fixed64", WireType.FIXED64),
    SFIXED64("sfixed64", WireType.FIXED64),
    DOUBLE("double", WireType.FIXED64),

    STRING("string", WireType.LENGTH_DELIMITED),
    BYTES("bytes", WireType.LENGTH_DELIMITED),
    MESSAGE("message", WireType.LENGTH_DELIMITED),

    PACKED_INT("packed_int", INT),
    PACKED_UINT("packed_uint", UINT),
    PACKED_SINT("packed_sint", SINT),
    PACKED_BOOL("packed_bool", BOOL),
    PACKED_FIXED32("packed_fixed32", FIXED32),
    PACKED_SFIXED32("packed_sfixed32", SFIXED32),
    PACKED_FLOAT("packed_float", FLOAT),
    PACKED_FIXED64("packed_fixed64", FIXED64),
    PACKED_SFIXED64("packed_sfixed64", SFIXED64),
    PACKED_DOUBLE("packed_double", DOUBLE),

    GROUP("group", WireType.START_GROUP);

    private static final Map<String, FieldType> BY_LABEL;

    static {
        Map<String, FieldType> byLabel = new HashMap<>();
        for (FieldType type : values()) {
            byLabel.put(type.label, type);
        }
        // Accept the aliases a user is most likely to type by hand.
        byLabel.put("int32", INT);
        byLabel.put("int64", INT);
        byLabel.put("uint32", UINT);
        byLabel.put("uint64", UINT);
        byLabel.put("sint32", SINT);
        byLabel.put("sint64", SINT);
        byLabel.put("boolean", BOOL);
        byLabel.put("str", STRING);
        byLabel.put("byte", BYTES);
        byLabel.put("msg", MESSAGE);
        byLabel.put("packed", PACKED_INT);
        byLabel.put("packed_int32", PACKED_INT);
        byLabel.put("packed_int64", PACKED_INT);
        byLabel.put("packed_uint32", PACKED_UINT);
        byLabel.put("packed_uint64", PACKED_UINT);
        byLabel.put("packed_sint32", PACKED_SINT);
        byLabel.put("packed_sint64", PACKED_SINT);
        BY_LABEL = Collections.unmodifiableMap(byLabel);
    }

    private final String label;
    private final WireType wireType;
    private final FieldType elementType;

    FieldType(String label, WireType wireType) {
        this.label = label;
        this.wireType = wireType;
        this.elementType = null;
    }

    /** Packed constructor: a packed field is length-delimited and holds elements of one type. */
    FieldType(String label, FieldType elementType) {
        this.label = label;
        this.wireType = WireType.LENGTH_DELIMITED;
        this.elementType = elementType;
    }

    /** The label used in the Type Definition JSON, e.g. {@code "sfixed32"}. */
    public String label() {
        return label;
    }

    /** The wire type a field of this type is encoded as. */
    public WireType wireType() {
        return wireType;
    }

    public boolean isMessageLike() {
        return this == MESSAGE || this == GROUP;
    }

    /** Whether this is a {@code packed_*} type holding a run of scalars. */
    public boolean isPacked() {
        return elementType != null;
    }

    /**
     * The type of each element of a packed field.
     *
     * @throws IllegalStateException if this is not a packed type
     */
    public FieldType elementType() {
        if (elementType == null) {
            throw new IllegalStateException(label + " is not a packed type");
        }
        return elementType;
    }

    /**
     * Resolves a label from a user-edited type definition.
     *
     * @throws ProtobufException if the label is not a recognised type
     */
    public static FieldType fromLabel(String label) throws ProtobufException {
        if (label == null) {
            throw new ProtobufException("Field type is missing");
        }
        FieldType type = BY_LABEL.get(label.trim().toLowerCase(Locale.ROOT));
        if (type == null) {
            throw new ProtobufException("Unknown field type: '" + label + "'");
        }
        return type;
    }
}
