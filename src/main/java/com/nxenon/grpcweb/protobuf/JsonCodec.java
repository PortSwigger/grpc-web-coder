package com.nxenon.grpcweb.protobuf;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.google.gson.JsonPrimitive;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Translates between the decoder's value/type model and the JSON text shown in the editor tabs.
 *
 * <h2>Value document</h2>
 * Keys are field names when the type definition supplies one and field numbers otherwise. A field
 * that occurred once is a scalar; a field that occurred more than once is an array. {@code bytes}
 * fields are hex strings, {@code message} and {@code group} fields are nested objects.
 *
 * <h2>Type definition document</h2>
 * <pre>{@code
 * {
 *   "1": { "type": "int" },
 *   "2": { "type": "string", "name": "username" },
 *   "3": { "type": "message", "message_typedef": { "1": { "type": "int" } } }
 * }
 * }</pre>
 * The shorthand {@code "1": "int"} is accepted for hand editing. An {@code alt_types} array may be
 * present as a hint about other readings of the same bytes; it is written by the decoder and
 * ignored when read back.
 */
public final class JsonCodec {

    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .serializeNulls()
            .disableHtmlEscaping()
            .create();

    private static final String KEY_TYPE = "type";
    private static final String KEY_NAME = "name";
    private static final String KEY_MESSAGE_TYPEDEF = "message_typedef";
    private static final String KEY_ALT_TYPES = "alt_types";

    private JsonCodec() {
    }

    // ------------------------------------------------------------ values out

    /** Renders a decoded message as pretty-printed JSON. */
    public static String valueToJson(MessageValue value, TypeDefinition typeDefinition) {
        return PRETTY.toJson(valueToJsonObject(value, typeDefinition));
    }

    private static JsonObject valueToJsonObject(MessageValue value, TypeDefinition typeDefinition) {
        JsonObject object = new JsonObject();
        for (Map.Entry<Integer, List<Object>> entry : value.fields().entrySet()) {
            int fieldNumber = entry.getKey();
            TypeDefinition.FieldDefinition definition = typeDefinition.get(fieldNumber);
            String key = definition != null && !definition.name().isEmpty()
                    ? definition.name()
                    : String.valueOf(fieldNumber);

            List<Object> values = entry.getValue();
            if (values.size() == 1) {
                object.add(key, toJsonElement(values.get(0), definition));
            } else {
                JsonArray array = new JsonArray();
                for (Object item : values) {
                    array.add(toJsonElement(item, definition));
                }
                object.add(key, array);
            }
        }
        return object;
    }

    private static JsonElement toJsonElement(Object value, TypeDefinition.FieldDefinition definition) {
        if (value instanceof List<?> packed) {
            JsonArray array = new JsonArray();
            for (Object element : packed) {
                array.add(toJsonElement(element, null));
            }
            return array;
        }
        if (value instanceof MessageValue nested) {
            TypeDefinition nestedDefinition = definition == null
                    ? new TypeDefinition()
                    : definition.messageTypeDefinition();
            return valueToJsonObject(nested, nestedDefinition == null ? new TypeDefinition() : nestedDefinition);
        }
        if (value instanceof byte[] bytes) {
            return new JsonPrimitive(Hex.encode(bytes));
        }
        if (value instanceof Boolean boolValue) {
            return new JsonPrimitive(boolValue);
        }
        if (value instanceof BigInteger big) {
            return new JsonPrimitive(big);
        }
        if (value instanceof Number number) {
            return new JsonPrimitive(number);
        }
        return new JsonPrimitive(String.valueOf(value));
    }

    // ------------------------------------------------------------- values in

    /**
     * Parses an edited value document back into a {@link MessageValue}.
     *
     * @throws ProtobufException if the JSON is malformed, or refers to a field the type definition
     *                           does not describe
     */
    public static MessageValue jsonToValue(String json, TypeDefinition typeDefinition)
            throws ProtobufException {
        JsonElement root = parse(json);
        if (!root.isJsonObject()) {
            throw new ProtobufException("The payload must be a JSON object");
        }
        return jsonToValue(root.getAsJsonObject(), typeDefinition);
    }

    private static MessageValue jsonToValue(JsonObject object, TypeDefinition typeDefinition)
            throws ProtobufException {
        MessageValue value = new MessageValue();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            String key = entry.getKey();
            TypeDefinition.FieldDefinition definition = typeDefinition.resolveKey(key);
            if (definition == null) {
                throw new ProtobufException("Field '" + key
                        + "' is in the payload but not in the type definition."
                        + " Add it to the type definition, or remove it from the payload.");
            }
            JsonElement element = entry.getValue();
            if (definition.type().isPacked()) {
                addPackedField(value, definition, key, element);
            } else if (element.isJsonArray()) {
                for (JsonElement item : element.getAsJsonArray()) {
                    value.add(definition.number(), fromJsonElement(item, definition, key));
                }
            } else {
                value.add(definition.number(), fromJsonElement(element, definition, key));
            }
        }
        return value;
    }

    /**
     * Reads a packed field, which is a JSON array of scalars.
     *
     * <p>A packed field that occurs more than once in the same message is an array of arrays, so a
     * nested array is read as a second occurrence rather than as an element.
     */
    private static void addPackedField(
            MessageValue value, TypeDefinition.FieldDefinition definition, String key,
            JsonElement element)
            throws ProtobufException {

        if (!element.isJsonArray()) {
            throw new ProtobufException("Field '" + key + "' is '" + definition.type().label()
                    + "' so its value must be a JSON array");
        }
        JsonArray array = element.getAsJsonArray();
        boolean occurrences = array.size() > 0 && array.get(0).isJsonArray();
        if (occurrences) {
            for (JsonElement occurrence : array) {
                if (!occurrence.isJsonArray()) {
                    throw new ProtobufException("Field '" + key
                            + "' mixes packed runs and bare values; use one or the other");
                }
                value.add(definition.number(), packedElements(occurrence.getAsJsonArray(),
                        definition, key));
            }
        } else {
            value.add(definition.number(), packedElements(array, definition, key));
        }
    }

    private static List<Object> packedElements(
            JsonArray array, TypeDefinition.FieldDefinition definition, String key)
            throws ProtobufException {

        FieldType elementType = definition.type().elementType();
        List<Object> elements = new ArrayList<>(array.size());
        for (JsonElement item : array) {
            if (!item.isJsonPrimitive()) {
                throw new ProtobufException(
                        "Every element of packed field '" + key + "' must be a scalar");
            }
            JsonPrimitive primitive = item.getAsJsonPrimitive();
            elements.add(switch (elementType) {
                case BOOL -> primitive.isBoolean()
                        ? (Object) primitive.getAsBoolean()
                        : (Object) primitive.getAsString();
                case FLOAT, DOUBLE -> primitive.isNumber()
                        ? (Object) primitive.getAsDouble()
                        : (Object) primitive.getAsString();
                default -> numberValue(primitive);
            });
        }
        return elements;
    }

    private static Object fromJsonElement(
            JsonElement element, TypeDefinition.FieldDefinition definition, String key)
            throws ProtobufException {

        if (element.isJsonNull()) {
            throw new ProtobufException("Field '" + key + "' is null, which protobuf cannot encode");
        }

        if (definition.type().isMessageLike()) {
            if (!element.isJsonObject()) {
                throw new ProtobufException("Field '" + key + "' is a '"
                        + definition.type().label() + "' so its value must be a JSON object");
            }
            TypeDefinition nested = definition.messageTypeDefinition();
            if (nested == null) {
                throw new ProtobufException("Field '" + key
                        + "' is a '" + definition.type().label()
                        + "' but the type definition has no message_typedef for it");
            }
            return jsonToValue(element.getAsJsonObject(), nested);
        }

        if (!element.isJsonPrimitive()) {
            throw new ProtobufException("Field '" + key + "' must be a scalar value");
        }
        JsonPrimitive primitive = element.getAsJsonPrimitive();

        return switch (definition.type()) {
            case STRING -> primitive.getAsString();
            case BYTES -> Hex.decode(primitive.getAsString());
            case BOOL -> primitive.isBoolean()
                    ? (Object) primitive.getAsBoolean()
                    : (Object) primitive.getAsString();
            case FLOAT, DOUBLE -> primitive.isNumber()
                    ? (Object) primitive.getAsDouble()
                    : (Object) primitive.getAsString();
            default -> numberValue(primitive);
        };
    }

    /** Reads an integral primitive without losing precision on 64-bit values. */
    private static Object numberValue(JsonPrimitive primitive) {
        if (primitive.isBoolean()) {
            return primitive.getAsBoolean();
        }
        if (primitive.isString()) {
            return primitive.getAsString();
        }
        try {
            return primitive.getAsBigInteger();
        } catch (NumberFormatException | ArithmeticException e) {
            return primitive.getAsDouble();
        }
    }

    // ----------------------------------------------------------- typedef out

    /** Renders a type definition as pretty-printed JSON for the Type Definition tab. */
    public static String typeDefinitionToJson(TypeDefinition typeDefinition) {
        return PRETTY.toJson(typeDefinitionToJsonObject(typeDefinition));
    }

    private static JsonObject typeDefinitionToJsonObject(TypeDefinition typeDefinition) {
        JsonObject object = new JsonObject();
        for (TypeDefinition.FieldDefinition definition : typeDefinition.fields().values()) {
            JsonObject field = new JsonObject();
            field.addProperty(KEY_TYPE, definition.type().label());
            if (!definition.name().isEmpty()) {
                field.addProperty(KEY_NAME, definition.name());
            }
            if (definition.type().isMessageLike() && definition.messageTypeDefinition() != null) {
                field.add(KEY_MESSAGE_TYPEDEF,
                        typeDefinitionToJsonObject(definition.messageTypeDefinition()));
            }
            if (!definition.alternativeTypes().isEmpty()) {
                JsonArray alternatives = new JsonArray();
                for (FieldType type : definition.alternativeTypes()) {
                    alternatives.add(type.label());
                }
                field.add(KEY_ALT_TYPES, alternatives);
            }
            object.add(String.valueOf(definition.number()), field);
        }
        return object;
    }

    // ------------------------------------------------------------ typedef in

    /**
     * Parses an edited type definition document.
     *
     * @throws ProtobufException if the JSON is malformed, a key is not a field number, or a type
     *                           label is not recognised
     */
    public static TypeDefinition jsonToTypeDefinition(String json) throws ProtobufException {
        JsonElement root = parse(json);
        if (!root.isJsonObject()) {
            throw new ProtobufException("The type definition must be a JSON object");
        }
        return jsonToTypeDefinition(root.getAsJsonObject());
    }

    private static TypeDefinition jsonToTypeDefinition(JsonObject object) throws ProtobufException {
        TypeDefinition typeDefinition = new TypeDefinition();
        for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
            int fieldNumber = parseFieldNumber(entry.getKey());
            JsonElement element = entry.getValue();

            // Shorthand form: "1": "int"
            if (element.isJsonPrimitive() && element.getAsJsonPrimitive().isString()) {
                typeDefinition.define(fieldNumber, FieldType.fromLabel(element.getAsString()));
                continue;
            }
            if (!element.isJsonObject()) {
                throw new ProtobufException("Type definition for field " + fieldNumber
                        + " must be an object like { \"type\": \"int\" } or a type name");
            }

            JsonObject field = element.getAsJsonObject();
            if (!field.has(KEY_TYPE)) {
                throw new ProtobufException(
                        "Type definition for field " + fieldNumber + " has no \"type\"");
            }
            FieldType type = FieldType.fromLabel(field.get(KEY_TYPE).getAsString());
            TypeDefinition.FieldDefinition definition = typeDefinition.define(fieldNumber, type);

            if (field.has(KEY_NAME) && !field.get(KEY_NAME).isJsonNull()) {
                definition.setName(field.get(KEY_NAME).getAsString());
            }
            if (type.isMessageLike()) {
                if (field.has(KEY_MESSAGE_TYPEDEF) && field.get(KEY_MESSAGE_TYPEDEF).isJsonObject()) {
                    definition.setMessageTypeDefinition(
                            jsonToTypeDefinition(field.getAsJsonObject(KEY_MESSAGE_TYPEDEF)));
                } else {
                    // An empty nested definition still lets the decoder infer the inner fields.
                    definition.setMessageTypeDefinition(new TypeDefinition());
                }
            }
            if (field.has(KEY_ALT_TYPES) && field.get(KEY_ALT_TYPES).isJsonArray()) {
                List<FieldType> alternatives = new ArrayList<>();
                for (JsonElement alternative : field.getAsJsonArray(KEY_ALT_TYPES)) {
                    try {
                        alternatives.add(FieldType.fromLabel(alternative.getAsString()));
                    } catch (ProtobufException ignored) {
                        // alt_types is only a hint, so an unrecognised entry is not worth failing on.
                    }
                }
                definition.setAlternativeTypes(alternatives);
            }
        }
        return typeDefinition;
    }

    private static int parseFieldNumber(String key) throws ProtobufException {
        try {
            int number = Integer.parseInt(key.trim());
            if (number < 1) {
                throw new ProtobufException("Field number must be 1 or greater, got " + number);
            }
            return number;
        } catch (NumberFormatException e) {
            throw new ProtobufException(
                    "Type definition keys must be field numbers, but found '" + key + "'");
        }
    }

    private static JsonElement parse(String json) throws ProtobufException {
        if (json == null || json.isBlank()) {
            throw new ProtobufException("No JSON to parse");
        }
        try {
            return JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new ProtobufException("Invalid JSON: " + e.getMessage(), e);
        }
    }
}
