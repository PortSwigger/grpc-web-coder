package com.nxenon.grpcweb.protobuf;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The decoded contents of a protobuf message: field number to the list of values seen for it.
 *
 * <p>Every field holds a list because protobuf cannot distinguish a singular field from a repeated
 * field with one element without the schema. {@link JsonCodec} renders single-element lists as
 * scalars and multi-element lists as JSON arrays.
 *
 * <p>Values are one of {@link Long} (varint and fixed types), {@link java.math.BigInteger}
 * (unsigned 64-bit that does not fit a signed long), {@link Double}, {@link Boolean},
 * {@link String}, {@code byte[]}, or a nested {@code MessageValue}.
 */
public final class MessageValue {

    private final LinkedHashMap<Integer, List<Object>> fields = new LinkedHashMap<>();

    public void add(int fieldNumber, Object value) {
        fields.computeIfAbsent(fieldNumber, key -> new ArrayList<>()).add(value);
    }

    public List<Object> get(int fieldNumber) {
        List<Object> values = fields.get(fieldNumber);
        return values == null ? Collections.emptyList() : Collections.unmodifiableList(values);
    }

    public boolean has(int fieldNumber) {
        return fields.containsKey(fieldNumber);
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public int size() {
        return fields.size();
    }

    /** Fields in wire order, each mapped to its values in wire order. */
    public Map<Integer, List<Object>> fields() {
        return Collections.unmodifiableMap(fields);
    }
}
