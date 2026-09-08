package com.nxenon.grpcweb.protobuf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The property that matters most: decoding a payload and re-encoding it without edits must give the
 * original bytes back. If that does not hold, forwarding an untouched request through the extension
 * changes what the server receives.
 */
class RoundTripTest {

    /**
     * The encoding of {@code TestMessage} from the reference {@code Test.proto} used by
     * blackboxprotobuf's own test suite. It exercises every protobuf type in one message: double,
     * float, int32/64, uint32/64, sint32/64, fixed32/64, sfixed32/64, bool, string, bytes, an
     * embedded message, and a repeated int32. Field numbers run up to 65536, so multi-byte tags are
     * covered too.
     */
    private static final String EVERY_TYPE_HEX =
            "09333333333333124015c3f54840200540028001098002800880040380080185101400000081200a0000"
            + "00000000008540ecffffff818001deffffffffffffff808002018280040c546573740a20537472696e67"
            + "8280080962797465730061616182801013120854657374313233341"
            + "9cdcccccccccc0040828020020205";

    @Test
    @DisplayName("a message using every protobuf type re-encodes to identical bytes")
    void everyTypeRoundTripsExactly() throws Exception {
        byte[] original = Hex.decode(EVERY_TYPE_HEX);

        ProtobufDecoder.Result decoded = ProtobufDecoder.decode(original);
        byte[] reencoded = ProtobufEncoder.encode(decoded.value(), decoded.typeDefinition());

        assertArrayEquals(original, reencoded,
                "expected " + Hex.encode(original) + "\nbut got  " + Hex.encode(reencoded));
    }

    @Test
    @DisplayName("that same message survives a full JSON round-trip through the editor tabs")
    void everyTypeRoundTripsThroughJson() throws Exception {
        byte[] original = Hex.decode(EVERY_TYPE_HEX);

        BlackboxProtobuf.Decoded decoded = BlackboxProtobuf.decode(original);
        byte[] reencoded =
                BlackboxProtobuf.encode(decoded.payloadJson(), decoded.typeDefinitionJson());

        assertArrayEquals(original, reencoded);
    }

    @Test
    @DisplayName("the reference message decodes to the field set the .proto declares")
    void everyTypeDecodesExpectedFields() throws Exception {
        ProtobufDecoder.Result decoded = ProtobufDecoder.decode(Hex.decode(EVERY_TYPE_HEX));
        TypeDefinition definition = decoded.typeDefinition();

        // testDouble = 1 (fixed64), testFloat = 2 (fixed32), testInt32 = 4 (varint)
        assertEquals(FieldType.FIXED64, definition.get(1).type());
        assertEquals(FieldType.FIXED32, definition.get(2).type());
        assertEquals(FieldType.INT, definition.get(4).type());
        // testString = 8192, testBytes = 16384, testEmbed = 32768
        assertEquals(FieldType.STRING, definition.get(8192).type());
        assertEquals("Test\n String", decoded.value().get(8192).get(0));
        assertEquals(FieldType.BYTES, definition.get(16384).type());
        assertEquals(FieldType.MESSAGE, definition.get(32768).type());

        // testRepeatedInt32 = 65536 is a proto3 repeated scalar, so it is packed: both values sit
        // inside one length-delimited field. Without a schema that is indistinguishable from
        // bytes, so it is decoded as bytes with packed_int offered as an alternative.
        assertEquals(FieldType.BYTES, definition.get(65536).type());
        assertEquals(1, decoded.value().get(65536).size());
        assertTrue(definition.get(65536).alternativeTypes().contains(FieldType.PACKED_INT));
    }

    @Test
    @DisplayName("selecting packed_int reveals the elements of a packed repeated field")
    void packedIntOverrideRevealsElements() throws Exception {
        TypeDefinition definition = new TypeDefinition();
        definition.define(65536, FieldType.PACKED_INT);

        MessageValue value = ProtobufDecoder.decodeWith(
                // field 65536, wire 2, length 2, varints 2 and 5
                Hex.decode("828020020205"), definition);

        assertEquals(1, value.get(65536).size());
        assertEquals(java.util.List.of(2L, 5L), value.get(65536).get(0));
    }

    @Test
    @DisplayName("a packed field re-encodes to the bytes it came from")
    void packedFieldRoundTrips() throws Exception {
        byte[] original = Hex.decode("828020020205");

        TypeDefinition definition = new TypeDefinition();
        definition.define(65536, FieldType.PACKED_INT);
        MessageValue value = ProtobufDecoder.decodeWith(original, definition);

        assertArrayEquals(original, ProtobufEncoder.encode(value, definition));
    }

    @Test
    @DisplayName("a packed field round-trips through JSON as an array")
    void packedFieldRoundTripsThroughJson() throws Exception {
        byte[] original = Hex.decode("828020020205");
        String typeDefinitionJson = "{\"65536\": {\"type\": \"packed_int\"}}";

        TypeDefinition definition = JsonCodec.jsonToTypeDefinition(typeDefinitionJson);
        MessageValue value = ProtobufDecoder.decodeWith(original, definition);
        String payloadJson = JsonCodec.valueToJson(value, definition);

        assertTrue(payloadJson.contains("2"), payloadJson);
        assertArrayEquals(original, BlackboxProtobuf.encode(payloadJson, typeDefinitionJson));
    }

    @Test
    @DisplayName("editing a packed array changes the encoded elements")
    void packedArrayIsEditable() throws Exception {
        String typeDefinitionJson = "{\"65536\": {\"type\": \"packed_int\"}}";
        byte[] encoded = BlackboxProtobuf.encode("{\"65536\": [1, 2, 3]}", typeDefinitionJson);
        // field 65536, wire 2, length 3, varints 1, 2, 3
        assertArrayEquals(Hex.decode("828020030102 03".replace(" ", "")), encoded);
    }

    @Test
    @DisplayName("packed fixed32 and fixed64 runs round-trip")
    void packedFixedRunsRoundTrip() throws Exception {
        // field 1, wire 2, length 8, two little-endian 32-bit values (1 and 2)
        byte[] fixed32 = Hex.decode("0a080100000002000000");
        TypeDefinition definition32 = new TypeDefinition();
        definition32.define(1, FieldType.PACKED_FIXED32);
        MessageValue value32 = ProtobufDecoder.decodeWith(fixed32, definition32);
        assertEquals(java.util.List.of(1L, 2L), value32.get(1).get(0));
        assertArrayEquals(fixed32, ProtobufEncoder.encode(value32, definition32));

        // field 1, wire 2, length 8, one little-endian 64-bit value
        byte[] fixed64 = Hex.decode("0a080100000000000000");
        TypeDefinition definition64 = new TypeDefinition();
        definition64.define(1, FieldType.PACKED_FIXED64);
        MessageValue value64 = ProtobufDecoder.decodeWith(fixed64, definition64);
        assertEquals(java.util.List.of(1L), value64.get(1).get(0));
        assertArrayEquals(fixed64, ProtobufEncoder.encode(value64, definition64));
    }

    @Test
    @DisplayName("a payload that is not a whole number of packed elements is refused")
    void malformedPackedPayloadIsRefused() throws Exception {
        TypeDefinition definition = new TypeDefinition();
        definition.define(1, FieldType.PACKED_FIXED32);
        // three bytes cannot be a run of 4-byte values
        ProtobufException e = assertThrows(ProtobufException.class,
                () -> ProtobufDecoder.decodeWith(Hex.decode("0a03010203"), definition));
        assertTrue(e.getMessage().contains("packed_fixed32"), e.getMessage());
    }

    @Test
    @DisplayName("a bytes field containing a NUL and high bytes survives editing")
    void bytesFieldSurvivesRoundTrip() throws Exception {
        ProtobufDecoder.Result decoded = ProtobufDecoder.decode(Hex.decode(EVERY_TYPE_HEX));
        byte[] testBytes = (byte[]) decoded.value().get(16384).get(0);
        // "bytes\0aaa" from the reference proto: the NUL is exactly what broke the Jython version.
        assertArrayEquals("bytes\u0000aaa".getBytes(java.nio.charset.StandardCharsets.ISO_8859_1),
                testBytes);
    }

    @ParameterizedTest
    @DisplayName("hand-written payloads of each shape round-trip exactly")
    @ValueSource(strings = {
            "",                                 // empty message
            "089601",                           // varint
            "08ffffffffffffffffff01",           // maximum varint
            "0d0000803f",                       // fixed32
            "093333333333331240",               // fixed64
            "0a0774657374696e67",               // string
            "0a02fffe",                         // bytes (invalid UTF-8)
            "0a00",                             // empty length-delimited
            "0a03089601",                       // nested message
            "080108020803",                     // repeated varint
            "0b10050c",                         // group
            "180308011002",                     // out-of-order field numbers
            "0a0568656c6c6f",                   // string that partially parses as a message
            "f8ffffff0f01",                     // maximum field number (2^29-1)
    })
    void payloadShapesRoundTrip(String hex) throws Exception {
        byte[] original = Hex.decode(hex);
        ProtobufDecoder.Result decoded = ProtobufDecoder.decode(original);
        byte[] reencoded = ProtobufEncoder.encode(decoded.value(), decoded.typeDefinition());
        assertArrayEquals(original, reencoded,
                "in: " + hex + " out: " + Hex.encode(reencoded));
    }

    @ParameterizedTest
    @DisplayName("randomly generated messages round-trip exactly")
    @ValueSource(longs = {1L, 2L, 3L, 42L, 1337L, 99991L})
    void randomMessagesRoundTrip(long seed) throws Exception {
        Random random = new Random(seed);
        for (int iteration = 0; iteration < 200; iteration++) {
            byte[] original = randomMessage(random, 0);
            ProtobufDecoder.Result decoded = ProtobufDecoder.decode(original);
            byte[] reencoded = ProtobufEncoder.encode(decoded.value(), decoded.typeDefinition());
            assertArrayEquals(original, reencoded,
                    "seed " + seed + " iteration " + iteration
                            + "\nin:  " + Hex.encode(original)
                            + "\nout: " + Hex.encode(reencoded));
        }
    }

    @Test
    @DisplayName("decoding arbitrary bytes never throws anything but ProtobufException")
    void arbitraryBytesFailCleanly() {
        Random random = new Random(7);
        for (int iteration = 0; iteration < 5000; iteration++) {
            byte[] noise = new byte[random.nextInt(64)];
            random.nextBytes(noise);
            try {
                ProtobufDecoder.Result decoded = ProtobufDecoder.decode(noise);
                // If it decoded, it must also re-encode without blowing up.
                ProtobufEncoder.encode(decoded.value(), decoded.typeDefinition());
            } catch (ProtobufException expected) {
                // A refusal is the correct outcome for input that is not protobuf.
            } catch (Throwable unexpected) {
                throw new AssertionError(
                        "decoding " + Hex.encode(noise) + " threw " + unexpected, unexpected);
            }
        }
    }

    @Test
    @DisplayName("a payload edited through JSON produces the change and nothing else")
    void editingOneFieldChangesOnlyThatField() throws Exception {
        // field 1 = "alice", field 2 = 30
        byte[] original = Hex.decode("0a05616c696365101e");

        BlackboxProtobuf.Decoded decoded = BlackboxProtobuf.decode(original);
        assertTrue(decoded.payloadJson().contains("alice"));

        String edited = decoded.payloadJson().replace("alice", "bob");
        byte[] reencoded = BlackboxProtobuf.encode(edited, decoded.typeDefinitionJson());

        assertArrayEquals(Hex.decode("0a03626f62101e"), reencoded);
    }

    /** Builds a random but valid protobuf message, nesting up to three levels deep. */
    private static byte[] randomMessage(Random random, int depth) throws Exception {
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int fieldCount = 1 + random.nextInt(5);

        // Field numbers are distinct within a message. A real protobuf schema cannot give one
        // field number two different wire types, and a type definition keyed by field number
        // cannot describe it either -- that case is covered by its own test in
        // ProtobufDecoderTest instead.
        java.util.List<Integer> fieldNumbers = new java.util.ArrayList<>();
        while (fieldNumbers.size() < fieldCount) {
            int candidate = 1 + random.nextInt(2000);
            if (!fieldNumbers.contains(candidate)) {
                fieldNumbers.add(candidate);
            }
        }

        for (int i = 0; i < fieldCount; i++) {
            int fieldNumber = fieldNumbers.get(i);
            int choice = random.nextInt(depth >= 2 ? 4 : 5);
            switch (choice) {
                case 0 -> { // varint
                    writeTag(out, fieldNumber, 0);
                    writeVarint(out, random.nextLong());
                }
                case 1 -> { // fixed32
                    writeTag(out, fieldNumber, 5);
                    int value = random.nextInt();
                    for (int b = 0; b < 4; b++) {
                        out.write((value >>> (8 * b)) & 0xFF);
                    }
                }
                case 2 -> { // fixed64
                    writeTag(out, fieldNumber, 1);
                    long value = random.nextLong();
                    for (int b = 0; b < 8; b++) {
                        out.write((int) ((value >>> (8 * b)) & 0xFF));
                    }
                }
                case 3 -> { // length-delimited with random bytes
                    writeTag(out, fieldNumber, 2);
                    byte[] payload = new byte[random.nextInt(12)];
                    random.nextBytes(payload);
                    writeVarint(out, payload.length);
                    out.write(payload);
                }
                default -> { // nested message
                    writeTag(out, fieldNumber, 2);
                    byte[] nested = randomMessage(random, depth + 1);
                    writeVarint(out, nested.length);
                    out.write(nested);
                }
            }
        }
        return out.toByteArray();
    }

    private static void writeTag(java.io.ByteArrayOutputStream out, int fieldNumber, int wireType) {
        writeVarint(out, ((long) fieldNumber << 3) | wireType);
    }

    private static void writeVarint(java.io.ByteArrayOutputStream out, long value) {
        while (true) {
            if ((value & ~0x7FL) == 0) {
                out.write((int) value);
                return;
            }
            out.write((int) ((value & 0x7F) | 0x80));
            value >>>= 7;
        }
    }
}
