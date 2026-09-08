package com.nxenon.grpcweb.protobuf;

/**
 * Convenience entry point tying the decoder, encoder and JSON layers together.
 *
 * <p>This is the surface the UI and the tests use; the classes behind it can be exercised
 * individually when a test needs to look at the value or type model directly.
 */
public final class BlackboxProtobuf {

    private BlackboxProtobuf() {
    }

    /** A decoded message rendered as JSON, with the type definition that produced it. */
    public static final class Decoded {
        private final String payloadJson;
        private final String typeDefinitionJson;
        private final TypeDefinition typeDefinition;
        private final MessageValue value;

        Decoded(String payloadJson, String typeDefinitionJson, TypeDefinition typeDefinition,
                MessageValue value) {
            this.payloadJson = payloadJson;
            this.typeDefinitionJson = typeDefinitionJson;
            this.typeDefinition = typeDefinition;
            this.value = value;
        }

        public String payloadJson() {
            return payloadJson;
        }

        public String typeDefinitionJson() {
            return typeDefinitionJson;
        }

        public TypeDefinition typeDefinition() {
            return typeDefinition;
        }

        public MessageValue value() {
            return value;
        }
    }

    /** Decodes a protobuf message, inferring its type definition. */
    public static Decoded decode(byte[] message) throws ProtobufException {
        ProtobufDecoder.Result result = ProtobufDecoder.decode(message);
        return new Decoded(
                JsonCodec.valueToJson(result.value(), result.typeDefinition()),
                JsonCodec.typeDefinitionToJson(result.typeDefinition()),
                result.typeDefinition(),
                result.value());
    }

    /**
     * Decodes a protobuf message using a type definition the user has supplied, so their chosen
     * field types are respected instead of being re-inferred.
     */
    public static Decoded decodeWith(byte[] message, TypeDefinition typeDefinition)
            throws ProtobufException {
        MessageValue value = ProtobufDecoder.decodeWith(message, typeDefinition);
        return new Decoded(
                JsonCodec.valueToJson(value, typeDefinition),
                JsonCodec.typeDefinitionToJson(typeDefinition),
                typeDefinition,
                value);
    }

    /** Encodes an edited JSON payload back to protobuf using the given type definition JSON. */
    public static byte[] encode(String payloadJson, String typeDefinitionJson)
            throws ProtobufException {
        TypeDefinition typeDefinition = JsonCodec.jsonToTypeDefinition(typeDefinitionJson);
        MessageValue value = JsonCodec.jsonToValue(payloadJson, typeDefinition);
        return ProtobufEncoder.encode(value, typeDefinition);
    }
}
