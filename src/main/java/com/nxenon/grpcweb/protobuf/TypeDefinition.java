package com.nxenon.grpcweb.protobuf;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A recursive description of how each field number in a message should be interpreted.
 *
 * <p>This is the Java counterpart of blackboxprotobuf's "typedef". It is produced by
 * {@link ProtobufDecoder} as a best guess, shown to the user in the Type Definition tab, and fed
 * back into {@link ProtobufEncoder} so an edited payload re-encodes with the same field types the
 * server expects.
 *
 * <p>Field order is preserved (insertion order of first occurrence on the wire) so that re-encoding
 * a message that was not modified reproduces the original field ordering.
 */
public final class TypeDefinition {

    private final LinkedHashMap<Integer, FieldDefinition> fields = new LinkedHashMap<>();

    /** A single field's interpretation. */
    public static final class FieldDefinition {
        private final int number;
        private String name;
        private FieldType type;
        private TypeDefinition messageTypeDefinition;
        private List<FieldType> alternativeTypes = Collections.emptyList();

        FieldDefinition(int number, FieldType type) {
            this.number = number;
            this.type = type;
        }

        public int number() {
            return number;
        }

        /** Optional friendly name; empty when unknown, which is the default for a decoded payload. */
        public String name() {
            return name == null ? "" : name;
        }

        public void setName(String name) {
            this.name = name;
        }

        public FieldType type() {
            return type;
        }

        public void setType(FieldType type) {
            this.type = type;
        }

        /** Nested definition for {@code message} and {@code group} fields, otherwise {@code null}. */
        public TypeDefinition messageTypeDefinition() {
            return messageTypeDefinition;
        }

        public void setMessageTypeDefinition(TypeDefinition messageTypeDefinition) {
            this.messageTypeDefinition = messageTypeDefinition;
        }

        /**
         * Other types the same bytes could legitimately have been. Purely informational: it is
         * written into the Type Definition JSON as a hint so the user knows a length-delimited field
         * guessed as {@code message} could be forced to {@code string}, but the encoder ignores it.
         */
        public List<FieldType> alternativeTypes() {
            return alternativeTypes;
        }

        public void setAlternativeTypes(List<FieldType> alternativeTypes) {
            this.alternativeTypes = alternativeTypes == null
                    ? Collections.emptyList()
                    : List.copyOf(alternativeTypes);
        }
    }

    /** Creates and registers a definition for {@code number}, replacing any existing entry. */
    public FieldDefinition define(int number, FieldType type) {
        FieldDefinition definition = new FieldDefinition(number, type);
        fields.put(number, definition);
        return definition;
    }

    public void put(FieldDefinition definition) {
        fields.put(definition.number(), definition);
    }

    public FieldDefinition get(int number) {
        return fields.get(number);
    }

    public boolean isEmpty() {
        return fields.isEmpty();
    }

    public int size() {
        return fields.size();
    }

    /** Field definitions in wire order. */
    public Map<Integer, FieldDefinition> fields() {
        return Collections.unmodifiableMap(fields);
    }

    /**
     * Looks up a field by the key used in the decoded JSON document, which is the field's name when
     * one is set and its number otherwise.
     *
     * @return the matching definition, or {@code null} if no field matches
     */
    public FieldDefinition resolveKey(String key) {
        for (FieldDefinition definition : fields.values()) {
            if (!definition.name().isEmpty() && definition.name().equals(key)) {
                return definition;
            }
        }
        try {
            return fields.get(Integer.parseInt(key.trim()));
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
