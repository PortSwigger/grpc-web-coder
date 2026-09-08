package com.nxenon.grpcweb.protobuf;

import com.google.protobuf.CodedOutputStream;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

/**
 * Encodes a {@link MessageValue} back to protobuf wire format using a {@link TypeDefinition}.
 *
 * <p>Fields are written in the order they appear in the message, and all values of a repeated field
 * are written consecutively. For a payload that was decoded and not modified this reproduces the
 * original bytes exactly, provided the original used minimal varint encoding and did not interleave
 * repeated fields — which is what every mainstream protobuf implementation emits.
 */
public final class ProtobufEncoder {

    private ProtobufEncoder() {
    }

    /**
     * Encodes a message.
     *
     * @param value          the field values to write
     * @param typeDefinition the type of each field; every field present in {@code value} must have
     *                       an entry
     * @throws ProtobufException if a field has no definition, or a value does not fit its type
     */
    public static byte[] encode(MessageValue value, TypeDefinition typeDefinition)
            throws ProtobufException {
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(buffer);
        try {
            writeMessage(value, typeDefinition, output);
            output.flush();
        } catch (IOException e) {
            throw new ProtobufException("Failed to encode message: " + e.getMessage(), e);
        }
        return buffer.toByteArray();
    }

    private static void writeMessage(
            MessageValue value, TypeDefinition typeDefinition, CodedOutputStream output)
            throws IOException, ProtobufException {

        for (Map.Entry<Integer, List<Object>> entry : value.fields().entrySet()) {
            int fieldNumber = entry.getKey();
            TypeDefinition.FieldDefinition definition = typeDefinition.get(fieldNumber);
            if (definition == null) {
                throw new ProtobufException(
                        "Field " + fieldNumber + " has no entry in the type definition");
            }
            for (Object fieldValue : entry.getValue()) {
                writeField(fieldNumber, fieldValue, definition, output);
            }
        }
    }

    private static void writeField(
            int fieldNumber,
            Object fieldValue,
            TypeDefinition.FieldDefinition definition,
            CodedOutputStream output)
            throws IOException, ProtobufException {

        FieldType type = definition.type();
        switch (type.wireType()) {
            case VARINT -> {
                output.writeTag(fieldNumber, WireType.VARINT.value());
                output.writeUInt64NoTag(ValueCodec.valueToVarint(fieldValue, type));
            }
            case FIXED32 -> {
                output.writeTag(fieldNumber, WireType.FIXED32.value());
                output.writeFixed32NoTag(ValueCodec.valueToFixed32(fieldValue, type));
            }
            case FIXED64 -> {
                output.writeTag(fieldNumber, WireType.FIXED64.value());
                output.writeFixed64NoTag(ValueCodec.valueToFixed64(fieldValue, type));
            }
            case LENGTH_DELIMITED -> {
                byte[] payload = lengthDelimitedPayload(fieldNumber, fieldValue, definition);
                output.writeTag(fieldNumber, WireType.LENGTH_DELIMITED.value());
                output.writeUInt32NoTag(payload.length);
                output.writeRawBytes(payload);
            }
            case START_GROUP -> {
                if (!(fieldValue instanceof MessageValue nestedValue)) {
                    throw new ProtobufException(
                            "Field " + fieldNumber + " is a 'group' but its value is not an object");
                }
                TypeDefinition nestedDefinition = definition.messageTypeDefinition();
                if (nestedDefinition == null) {
                    throw new ProtobufException(
                            "Field " + fieldNumber + " is a 'group' but has no nested type definition");
                }
                output.writeTag(fieldNumber, WireType.START_GROUP.value());
                writeMessage(nestedValue, nestedDefinition, output);
                output.writeTag(fieldNumber, WireType.END_GROUP.value());
            }
            default -> throw new ProtobufException("Cannot encode wire type " + type.wireType());
        }
    }

    private static byte[] lengthDelimitedPayload(
            int fieldNumber, Object fieldValue, TypeDefinition.FieldDefinition definition)
            throws ProtobufException {

        if (definition.type().isPacked()) {
            return packedPayload(fieldNumber, fieldValue, definition.type());
        }
        switch (definition.type()) {
            case STRING -> {
                if (fieldValue instanceof String text) {
                    return text.getBytes(StandardCharsets.UTF_8);
                }
                if (fieldValue instanceof byte[] bytes) {
                    return bytes;
                }
                throw new ProtobufException("Field " + fieldNumber
                        + " is a 'string' but its value is not text");
            }
            case BYTES -> {
                if (fieldValue instanceof byte[] bytes) {
                    return bytes;
                }
                if (fieldValue instanceof String text) {
                    return Hex.decode(text);
                }
                throw new ProtobufException("Field " + fieldNumber
                        + " is 'bytes' but its value is not a hex string");
            }
            case MESSAGE -> {
                if (!(fieldValue instanceof MessageValue nestedValue)) {
                    throw new ProtobufException("Field " + fieldNumber
                            + " is a 'message' but its value is not an object");
                }
                TypeDefinition nestedDefinition = definition.messageTypeDefinition();
                if (nestedDefinition == null) {
                    throw new ProtobufException("Field " + fieldNumber
                            + " is a 'message' but has no nested type definition");
                }
                return encode(nestedValue, nestedDefinition);
            }
            default -> throw new ProtobufException(
                    "'" + definition.type().label() + "' is not a length-delimited type");
        }
    }

    /** Writes a packed run of scalars as the payload of one length-delimited field. */
    private static byte[] packedPayload(int fieldNumber, Object fieldValue, FieldType packedType)
            throws ProtobufException {

        if (!(fieldValue instanceof List<?> elements)) {
            throw new ProtobufException("Field " + fieldNumber + " is '" + packedType.label()
                    + "' so its value must be a JSON array");
        }
        FieldType elementType = packedType.elementType();
        ByteArrayOutputStream buffer = new ByteArrayOutputStream();
        CodedOutputStream output = CodedOutputStream.newInstance(buffer);
        try {
            for (Object element : elements) {
                switch (elementType.wireType()) {
                    case VARINT ->
                            output.writeUInt64NoTag(ValueCodec.valueToVarint(element, elementType));
                    case FIXED32 ->
                            output.writeFixed32NoTag(ValueCodec.valueToFixed32(element, elementType));
                    case FIXED64 ->
                            output.writeFixed64NoTag(ValueCodec.valueToFixed64(element, elementType));
                    default -> throw new ProtobufException(
                            "'" + packedType.label() + "' cannot be packed");
                }
            }
            output.flush();
        } catch (IOException e) {
            throw new ProtobufException(
                    "Failed to encode packed field " + fieldNumber + ": " + e.getMessage(), e);
        }
        return buffer.toByteArray();
    }
}
