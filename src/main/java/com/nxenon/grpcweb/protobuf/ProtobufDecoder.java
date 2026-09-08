package com.nxenon.grpcweb.protobuf;

import com.google.protobuf.CodedInputStream;
import com.google.protobuf.WireFormat;

import java.io.IOException;
import java.util.ArrayList;
import java.nio.ByteBuffer;
import java.nio.CharBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CharsetDecoder;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Decodes protobuf wire data without a {@code .proto} schema, inferring a field type for each field
 * as it goes.
 *
 * <p>Wire-level reading is delegated to {@link CodedInputStream} from protobuf-java, which is
 * hardened against malformed input (varint overflow, truncated fields, runaway recursion). This
 * class supplies only the part protobuf-java cannot: guessing what each field <em>means</em>.
 *
 * <h2>Inference rules</h2>
 * <ul>
 *   <li>{@code VARINT} becomes {@link FieldType#INT}. It could equally be {@code uint}, {@code sint}
 *       or {@code bool}; {@code int} round-trips all of them byte-for-byte, so it is the safe guess.
 *   <li>{@code FIXED32}/{@code FIXED64} become {@link FieldType#FIXED32}/{@link FieldType#FIXED64},
 *       with {@code float}/{@code double} offered as alternatives.
 *   <li>{@code LENGTH_DELIMITED} is tried as a nested message first, then as a UTF-8 string, and
 *       falls back to raw bytes. The nested-message attempt is strict: see
 *       {@link #tryDecodeMessage}.
 *   <li>{@code START_GROUP} becomes {@link FieldType#GROUP}, decoded recursively.
 * </ul>
 *
 * <p>A length-delimited field that parses cleanly as a message is reported as a message even when it
 * is also valid UTF-8, matching blackboxprotobuf's behaviour. The competing interpretation is
 * recorded in {@link TypeDefinition.FieldDefinition#alternativeTypes()} so the user can override it
 * in the Type Definition tab.
 */
public final class ProtobufDecoder {

    /**
     * Maximum nesting depth for the speculative "is this length-delimited field a message?" probe.
     * Deliberately lower than protobuf-java's own 100-deep limit: each level here is speculative
     * work over attacker-controlled bytes, and real gRPC-Web messages do not nest anywhere near
     * this deep.
     */
    private static final int MAX_DEPTH = 32;

    /** Largest field number permitted by the protobuf spec (2^29 - 1). */
    private static final int MAX_FIELD_NUMBER = 536_870_911;

    private ProtobufDecoder() {
    }

    /** A decoded message paired with the type definition that was inferred for it. */
    public static final class Result {
        private final MessageValue value;
        private final TypeDefinition typeDefinition;

        Result(MessageValue value, TypeDefinition typeDefinition) {
            this.value = value;
            this.typeDefinition = typeDefinition;
        }

        public MessageValue value() {
            return value;
        }

        public TypeDefinition typeDefinition() {
            return typeDefinition;
        }
    }

    /**
     * Decodes a complete protobuf message, inferring types.
     *
     * @param data the message bytes; an empty array decodes to an empty message
     * @throws ProtobufException if {@code data} is not a well-formed protobuf message
     */
    public static Result decode(byte[] data) throws ProtobufException {
        if (data == null) {
            throw new ProtobufException("No data to decode");
        }
        MessageValue value = new MessageValue();
        TypeDefinition typeDefinition = new TypeDefinition();
        try {
            CodedInputStream input = CodedInputStream.newInstance(data);
            readMessage(input, value, typeDefinition, 0, 0);
            if (!input.isAtEnd()) {
                throw new ProtobufException("Trailing bytes after end of message");
            }
        } catch (IOException e) {
            throw new ProtobufException("Not a valid protobuf message: " + e.getMessage(), e);
        }
        return new Result(value, typeDefinition);
    }

    /**
     * Decodes a message against a known type definition, without inference.
     *
     * <p>Used when the user has edited the type definition: the field types they chose are honoured
     * rather than re-guessed, so a field they changed from {@code message} to {@code string} stays a
     * string on the next redraw.
     *
     * @throws ProtobufException if the data does not match the definition
     */
    public static MessageValue decodeWith(byte[] data, TypeDefinition typeDefinition)
            throws ProtobufException {
        if (data == null) {
            throw new ProtobufException("No data to decode");
        }
        MessageValue value = new MessageValue();
        try {
            CodedInputStream input = CodedInputStream.newInstance(data);
            readMessage(input, value, typeDefinition, 0, 0);
            if (!input.isAtEnd()) {
                throw new ProtobufException("Trailing bytes after end of message");
            }
        } catch (IOException e) {
            throw new ProtobufException("Data does not match type definition: " + e.getMessage(), e);
        }
        return value;
    }

    /**
     * Reads fields until the end of the stream, or until the {@code END_GROUP} tag matching
     * {@code groupFieldNumber} when reading a group body.
     *
     * <p>The type definition is used where it already has an entry for a field and extended with an
     * inferred entry where it does not, which is what lets the same routine serve both
     * {@link #decode} and {@link #decodeWith}.
     */
    private static void readMessage(
            CodedInputStream input,
            MessageValue value,
            TypeDefinition typeDefinition,
            int depth,
            int groupFieldNumber)
            throws IOException, ProtobufException {

        if (depth > MAX_DEPTH) {
            throw new ProtobufException("Message nesting deeper than " + MAX_DEPTH + " levels");
        }

        while (true) {
            int tag;
            if (groupFieldNumber == 0) {
                if (input.isAtEnd()) {
                    return;
                }
                tag = input.readTag();
                if (tag == 0) {
                    return;
                }
            } else {
                tag = input.readTag();
                if (tag == 0) {
                    throw new ProtobufException("Unterminated group " + groupFieldNumber);
                }
            }

            int fieldNumber = WireFormat.getTagFieldNumber(tag);
            WireType wireType = WireType.fromTag(tag);

            if (wireType == WireType.END_GROUP) {
                if (fieldNumber != groupFieldNumber) {
                    throw new ProtobufException(
                            "Mismatched group end: expected " + groupFieldNumber + " but found " + fieldNumber);
                }
                return;
            }

            if (fieldNumber < 1 || fieldNumber > MAX_FIELD_NUMBER) {
                throw new ProtobufException("Field number out of range: " + fieldNumber);
            }

            TypeDefinition.FieldDefinition definition = typeDefinition.get(fieldNumber);
            if (definition != null && definition.type().wireType() != wireType) {
                throw new ProtobufException("Field " + fieldNumber + " is typed as '"
                        + definition.type().label() + "' (wire type "
                        + definition.type().wireType() + ") but occurs on the wire as "
                        + wireType + ". A single field number cannot carry two wire types;"
                        + " if you edited the type definition, correct or reset it.");
            }

            switch (wireType) {
                case VARINT -> {
                    long raw = input.readRawVarint64();
                    FieldType type = definition == null ? FieldType.INT : definition.type();
                    if (definition == null) {
                        typeDefinition.define(fieldNumber, FieldType.INT)
                                .setAlternativeTypes(List.of(FieldType.UINT, FieldType.SINT, FieldType.BOOL));
                    }
                    value.add(fieldNumber, ValueCodec.varintToValue(raw, type));
                }
                case FIXED32 -> {
                    int raw = input.readRawLittleEndian32();
                    FieldType type = definition == null ? FieldType.FIXED32 : definition.type();
                    if (definition == null) {
                        typeDefinition.define(fieldNumber, FieldType.FIXED32)
                                .setAlternativeTypes(List.of(FieldType.SFIXED32, FieldType.FLOAT));
                    }
                    value.add(fieldNumber, ValueCodec.fixed32ToValue(raw, type));
                }
                case FIXED64 -> {
                    long raw = input.readRawLittleEndian64();
                    FieldType type = definition == null ? FieldType.FIXED64 : definition.type();
                    if (definition == null) {
                        typeDefinition.define(fieldNumber, FieldType.FIXED64)
                                .setAlternativeTypes(List.of(FieldType.SFIXED64, FieldType.DOUBLE));
                    }
                    value.add(fieldNumber, ValueCodec.fixed64ToValue(raw, type));
                }
                case LENGTH_DELIMITED -> {
                    byte[] bytes = input.readByteArray();
                    readLengthDelimited(fieldNumber, bytes, value, typeDefinition, definition, depth);
                }
                case START_GROUP -> {
                    TypeDefinition nested;
                    if (definition == null) {
                        TypeDefinition.FieldDefinition created =
                                typeDefinition.define(fieldNumber, FieldType.GROUP);
                        nested = new TypeDefinition();
                        created.setMessageTypeDefinition(nested);
                    } else {
                        nested = definition.messageTypeDefinition();
                        if (nested == null) {
                            nested = new TypeDefinition();
                            definition.setMessageTypeDefinition(nested);
                        }
                    }
                    MessageValue groupValue = new MessageValue();
                    readMessage(input, groupValue, nested, depth + 1, fieldNumber);
                    value.add(fieldNumber, groupValue);
                }
                default -> throw new ProtobufException("Unexpected wire type " + wireType);
            }
        }
    }

    /**
     * Interprets a length-delimited field, either as the type the definition already states or by
     * guessing message, then string, then bytes.
     */
    private static void readLengthDelimited(
            int fieldNumber,
            byte[] bytes,
            MessageValue value,
            TypeDefinition typeDefinition,
            TypeDefinition.FieldDefinition existing,
            int depth)
            throws ProtobufException {

        if (existing != null) {
            if (existing.type().isPacked()) {
                value.add(fieldNumber, decodePacked(bytes, existing.type()));
                return;
            }
            switch (existing.type()) {
                case MESSAGE -> {
                    TypeDefinition nested = existing.messageTypeDefinition();
                    if (nested == null) {
                        nested = new TypeDefinition();
                        existing.setMessageTypeDefinition(nested);
                    }
                    value.add(fieldNumber, decodeWith(bytes, nested));
                }
                case STRING -> value.add(fieldNumber, decodeUtf8Strict(bytes));
                case BYTES -> value.add(fieldNumber, bytes);
                default -> throw new ProtobufException("Field " + fieldNumber + " has type '"
                        + existing.type().label() + "', which is not length-delimited");
            }
            return;
        }

        // An empty payload is ambiguous between an empty message, an empty string and empty bytes.
        // "bytes" is chosen because it re-encodes identically whatever the field really is.
        if (bytes.length == 0) {
            typeDefinition.define(fieldNumber, FieldType.BYTES)
                    .setAlternativeTypes(List.of(FieldType.STRING, FieldType.MESSAGE));
            value.add(fieldNumber, bytes);
            return;
        }

        Result nested = tryDecodeMessage(bytes, depth + 1);
        if (nested != null) {
            TypeDefinition.FieldDefinition definition =
                    typeDefinition.define(fieldNumber, FieldType.MESSAGE);
            definition.setMessageTypeDefinition(nested.typeDefinition());
            // Flag the ambiguity when the same bytes would also have been readable text, so the
            // user knows they can force 'string' if the message guess looks like nonsense.
            String asText = tryDecodeUtf8(bytes);
            if (asText != null && isPlausibleText(asText)) {
                definition.setAlternativeTypes(List.of(FieldType.STRING, FieldType.BYTES));
            } else {
                definition.setAlternativeTypes(List.of(FieldType.BYTES));
            }
            value.add(fieldNumber, nested.value());
            return;
        }

        String text = tryDecodeUtf8(bytes);
        if (text != null && isPlausibleText(text)) {
            typeDefinition.define(fieldNumber, FieldType.STRING)
                    .setAlternativeTypes(List.of(FieldType.BYTES));
            value.add(fieldNumber, text);
            return;
        }

        // Falls back to bytes, which is also where valid-UTF-8-but-binary data lands: a NUL or a
        // control character inside the text means it is far more likely a binary blob than a
        // string, and hex is both truthful and safe to edit.
        TypeDefinition.FieldDefinition definition =
                typeDefinition.define(fieldNumber, FieldType.BYTES);
        List<FieldType> alternatives = new ArrayList<>();
        if (text != null) {
            alternatives.add(FieldType.STRING);
        }
        alternatives.addAll(plausiblePackedTypes(bytes));
        definition.setAlternativeTypes(alternatives);
        value.add(fieldNumber, bytes);
    }

    /**
     * Reads a packed run of scalars.
     *
     * @throws ProtobufException if the payload is not a whole number of elements of the declared
     *                           element type
     */
    static List<Object> decodePacked(byte[] payload, FieldType packedType) throws ProtobufException {
        FieldType elementType = packedType.elementType();
        List<Object> values = new ArrayList<>();
        try {
            CodedInputStream input = CodedInputStream.newInstance(payload);
            switch (elementType.wireType()) {
                case VARINT -> {
                    while (!input.isAtEnd()) {
                        values.add(ValueCodec.varintToValue(input.readRawVarint64(), elementType));
                    }
                }
                case FIXED32 -> {
                    while (!input.isAtEnd()) {
                        values.add(ValueCodec.fixed32ToValue(
                                input.readRawLittleEndian32(), elementType));
                    }
                }
                case FIXED64 -> {
                    while (!input.isAtEnd()) {
                        values.add(ValueCodec.fixed64ToValue(
                                input.readRawLittleEndian64(), elementType));
                    }
                }
                default -> throw new ProtobufException(
                        "'" + packedType.label() + "' cannot be packed");
            }
        } catch (IOException e) {
            throw new ProtobufException("Field is declared as '" + packedType.label()
                    + "' but its payload is not a whole number of "
                    + elementType.label() + " values", e);
        }
        return values;
    }

    /**
     * Which packed types the given payload could legitimately be, for the {@code alt_types} hint.
     *
     * <p>A packed field is length-delimited, so it is indistinguishable from {@code bytes} on the
     * wire and must never be guessed. Listing the shapes that would parse lets the user pick the
     * right one without trial and error.
     */
    private static List<FieldType> plausiblePackedTypes(byte[] payload) {
        List<FieldType> types = new ArrayList<>();
        if (payload.length == 0) {
            return types;
        }
        if (parsesAsVarintRun(payload)) {
            types.add(FieldType.PACKED_INT);
        }
        if (payload.length % 4 == 0) {
            types.add(FieldType.PACKED_FIXED32);
        }
        if (payload.length % 8 == 0) {
            types.add(FieldType.PACKED_FIXED64);
        }
        return types;
    }

    /** Whether the payload is exactly a sequence of complete varints. */
    private static boolean parsesAsVarintRun(byte[] payload) {
        try {
            CodedInputStream input = CodedInputStream.newInstance(payload);
            while (!input.isAtEnd()) {
                input.readRawVarint64();
            }
            return true;
        } catch (IOException e) {
            return false;
        }
    }

    /**
     * Attempts a strict nested-message parse.
     *
     * <p>Strict means the bytes must consume exactly, every field number must be in range, every
     * wire type must be defined, and groups must balance. Anything less and short strings would
     * routinely be misread as messages: a lenient parser accepts almost any byte sequence as a
     * partial message.
     *
     * @return the decoded message, or {@code null} if the bytes are not a valid message
     */
    private static Result tryDecodeMessage(byte[] bytes, int depth) {
        if (depth > MAX_DEPTH) {
            return null;
        }
        try {
            MessageValue value = new MessageValue();
            TypeDefinition typeDefinition = new TypeDefinition();
            CodedInputStream input = CodedInputStream.newInstance(bytes);
            readMessage(input, value, typeDefinition, depth, 0);
            if (!input.isAtEnd()) {
                return null;
            }
            // A "message" with no fields tells the user nothing and loses the original bytes on
            // re-encode if they were really a string, so reject it and let string/bytes win.
            if (value.isEmpty()) {
                return null;
            }
            return new Result(value, typeDefinition);
        } catch (IOException | ProtobufException | RuntimeException e) {
            return null;
        }
    }

    /**
     * Decodes strict UTF-8, rejecting anything that is not well-formed rather than substituting
     * replacement characters. Public because the gRPC-Web layer uses it to decide whether a trailer
     * frame is readable text.
     *
     * @return the decoded string, or {@code null} if the bytes are not valid UTF-8
     */
    public static String tryDecodeUtf8(byte[] bytes) {
        CharsetDecoder decoder = StandardCharsets.UTF_8.newDecoder()
                .onMalformedInput(CodingErrorAction.REPORT)
                .onUnmappableCharacter(CodingErrorAction.REPORT);
        try {
            CharBuffer decoded = decoder.decode(ByteBuffer.wrap(bytes));
            return decoded.toString();
        } catch (CharacterCodingException e) {
            return null;
        }
    }

    private static String decodeUtf8Strict(byte[] bytes) throws ProtobufException {
        String text = tryDecodeUtf8(bytes);
        if (text == null) {
            throw new ProtobufException(
                    "Field is declared as 'string' but its bytes are not valid UTF-8. Use 'bytes'.");
        }
        return text;
    }

    /**
     * Whether valid UTF-8 also reads as text a human would recognise.
     *
     * <p>Valid UTF-8 is not the same as a string: the bytes {@code "bytes\0aaa"} decode without
     * error but hold a NUL, and a protobuf {@code bytes} field carrying a binary blob frequently
     * decodes cleanly by chance. Control characters other than tab, newline and carriage return are
     * therefore treated as evidence the field is really {@code bytes}, which keeps binary data in
     * hex where every byte is visible and editable.
     */
    private static boolean isPlausibleText(String text) {
        if (text.isEmpty()) {
            return false;
        }
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            boolean allowedControl = c == '\t' || c == '\n' || c == '\r';
            if (!allowedControl && (c < 0x20 || c == 0x7F)) {
                return false;
            }
        }
        return true;
    }
}
