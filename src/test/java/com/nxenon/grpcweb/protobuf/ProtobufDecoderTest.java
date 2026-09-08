package com.nxenon.grpcweb.protobuf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProtobufDecoderTest {

    private static byte[] hex(String hex) throws Exception {
        return Hex.decode(hex);
    }

    @Nested
    class ScalarInference {

        @Test
        @DisplayName("a varint field is guessed as int")
        void varintBecomesInt() throws Exception {
            // field 1, wire 0, value 150
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("089601"));
            assertEquals(FieldType.INT, result.typeDefinition().get(1).type());
            assertEquals(List.of(150L), result.value().get(1));
        }

        @Test
        @DisplayName("the varint guess records uint, sint and bool as alternatives")
        void varintOffersAlternatives() throws Exception {
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("089601"));
            assertEquals(
                    List.of(FieldType.UINT, FieldType.SINT, FieldType.BOOL),
                    result.typeDefinition().get(1).alternativeTypes());
        }

        @Test
        @DisplayName("a 32-bit fixed field is guessed as fixed32, offering float")
        void fixed32BecomesFixed32() throws Exception {
            // field 1, wire 5, bits of 1.0f
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("0d0000803f"));
            assertEquals(FieldType.FIXED32, result.typeDefinition().get(1).type());
            assertTrue(result.typeDefinition().get(1).alternativeTypes().contains(FieldType.FLOAT));
        }

        @Test
        @DisplayName("a 64-bit fixed field is guessed as fixed64, offering double")
        void fixed64BecomesFixed64() throws Exception {
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("093333333333331240"));
            assertEquals(FieldType.FIXED64, result.typeDefinition().get(1).type());
            assertTrue(result.typeDefinition().get(1).alternativeTypes().contains(FieldType.DOUBLE));
        }

        @Test
        @DisplayName("a large varint stays exact as an unsigned value when typed as uint")
        void largeVarintStaysExact() throws Exception {
            // field 1, wire 0, value 2^64-1
            ProtobufDecoder.Result inferred = ProtobufDecoder.decode(hex("08ffffffffffffffffff01"));
            assertEquals(List.of(-1L), inferred.value().get(1));

            TypeDefinition asUint = new TypeDefinition();
            asUint.define(1, FieldType.UINT);
            MessageValue value = ProtobufDecoder.decodeWith(hex("08ffffffffffffffffff01"), asUint);
            assertEquals(List.of(new BigInteger("18446744073709551615")), value.get(1));
        }
    }

    @Nested
    class LengthDelimitedInference {

        @Test
        @DisplayName("plain text that cannot parse as a message is guessed as string")
        void textBecomesString() throws Exception {
            // field 1, wire 2, "testing"
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("0a0774657374696e67"));
            assertEquals(FieldType.STRING, result.typeDefinition().get(1).type());
            assertEquals(List.of("testing"), result.value().get(1));
        }

        @Test
        @DisplayName("a valid nested message is guessed as message and decoded recursively")
        void nestedMessageIsDecoded() throws Exception {
            // field 1, wire 2, containing field 1 varint 150
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("0a03089601"));
            assertEquals(FieldType.MESSAGE, result.typeDefinition().get(1).type());

            MessageValue nested = assertInstanceOf(MessageValue.class, result.value().get(1).get(0));
            assertEquals(List.of(150L), nested.get(1));

            TypeDefinition nestedDefinition = result.typeDefinition().get(1).messageTypeDefinition();
            assertNotNull(nestedDefinition);
            assertEquals(FieldType.INT, nestedDefinition.get(1).type());
        }

        @Test
        @DisplayName("bytes that are neither a message nor UTF-8 fall back to bytes")
        void invalidUtf8BecomesBytes() throws Exception {
            // field 1, wire 2, 0xff 0xfe -- an invalid UTF-8 sequence and not a valid message
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("0a02fffe"));
            assertEquals(FieldType.BYTES, result.typeDefinition().get(1).type());
            assertArrayEquals(new byte[]{(byte) 0xFF, (byte) 0xFE},
                    (byte[]) result.value().get(1).get(0));
        }

        @Test
        @DisplayName("an empty length-delimited field becomes bytes, which re-encodes identically")
        void emptyBecomesBytes() throws Exception {
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("0a00"));
            assertEquals(FieldType.BYTES, result.typeDefinition().get(1).type());
            assertEquals(0, ((byte[]) result.value().get(1).get(0)).length);
        }

        @Test
        @DisplayName("a message guess that is also readable text advertises string as an alternative")
        void ambiguousFieldAdvertisesString() throws Exception {
            // Inner bytes 0x62 0x20 followed by 32 'a's are at once a valid message
            // (field 12, wire 2, length 32) and entirely printable ASCII: "b aaaa...".
            byte[] data = hex("0a226220" + "61".repeat(32));
            ProtobufDecoder.Result result = ProtobufDecoder.decode(data);
            assertEquals(FieldType.MESSAGE, result.typeDefinition().get(1).type());
            assertTrue(result.typeDefinition().get(1).alternativeTypes().contains(FieldType.STRING),
                    "a printable message should offer string so the user can override the guess");
        }

        @Test
        @DisplayName("valid UTF-8 holding a NUL is treated as bytes, not as a string")
        void utf8WithNulBecomesBytes() throws Exception {
            // field 1, wire 2, "bytes\0aaa": valid UTF-8, but the NUL marks it as binary.
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("0a09627974657300616161"));
            assertEquals(FieldType.BYTES, result.typeDefinition().get(1).type());
            assertTrue(result.typeDefinition().get(1).alternativeTypes().contains(FieldType.STRING),
                    "string stays available as an override since the bytes do decode as UTF-8");
        }

        @Test
        @DisplayName("tab, newline and carriage return do not disqualify a string")
        void ordinaryWhitespaceStaysAString() throws Exception {
            // field 1, wire 2, "a\tb\nc\rd"
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("0a076109620a630d64"));
            assertEquals(FieldType.STRING, result.typeDefinition().get(1).type());
            assertEquals("a\tb\nc\rd", result.value().get(1).get(0));
        }

        @Test
        @DisplayName("a sub-parse that leaves trailing bytes is not accepted as a message")
        void partialParseIsNotAMessage() throws Exception {
            // "hello" -- 0x68 reads as field 13 varint, then 0x6c is an END_GROUP mismatch
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("0a0568656c6c6f"));
            assertEquals(FieldType.STRING, result.typeDefinition().get(1).type());
            assertEquals(List.of("hello"), result.value().get(1));
        }
    }

    @Nested
    class RepeatedAndGroups {

        @Test
        @DisplayName("repeated occurrences of one field collect into a list in wire order")
        void repeatedFieldsCollect() throws Exception {
            // field 1 = 1, field 1 = 2, field 1 = 3
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("080108020803"));
            assertEquals(List.of(1L, 2L, 3L), result.value().get(1));
            assertEquals(1, result.typeDefinition().size());
        }

        @Test
        @DisplayName("a group is decoded as a nested message and its end tag consumed")
        void groupIsDecoded() throws Exception {
            // field 1 START_GROUP, field 2 varint 5, field 1 END_GROUP
            ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("0b10050c"));
            assertEquals(FieldType.GROUP, result.typeDefinition().get(1).type());
            MessageValue group = assertInstanceOf(MessageValue.class, result.value().get(1).get(0));
            assertEquals(List.of(5L), group.get(2));
        }

        @Test
        void unterminatedGroupIsRejected() {
            assertThrows(ProtobufException.class, () -> ProtobufDecoder.decode(hex("0b1005")));
        }

        @Test
        void mismatchedGroupEndIsRejected() {
            // opens group 1 but closes group 2
            assertThrows(ProtobufException.class, () -> ProtobufDecoder.decode(hex("0b100514")));
        }
    }

    @Nested
    class MalformedInput {

        @Test
        void emptyInputDecodesToAnEmptyMessage() throws Exception {
            ProtobufDecoder.Result result = ProtobufDecoder.decode(new byte[0]);
            assertTrue(result.value().isEmpty());
            assertTrue(result.typeDefinition().isEmpty());
        }

        @Test
        void nullInputIsRejected() {
            assertThrows(ProtobufException.class, () -> ProtobufDecoder.decode(null));
        }

        @Test
        @DisplayName("a truncated varint is rejected rather than silently returning a partial field")
        void truncatedVarintIsRejected() {
            assertThrows(ProtobufException.class, () -> ProtobufDecoder.decode(hex("08ff")));
        }

        @Test
        @DisplayName("a length-delimited field claiming more bytes than exist is rejected")
        void truncatedLengthDelimitedIsRejected() {
            assertThrows(ProtobufException.class, () -> ProtobufDecoder.decode(hex("0a0f6869")));
        }

        @Test
        @DisplayName("a reserved wire type is rejected")
        void reservedWireTypeIsRejected() {
            assertThrows(ProtobufException.class, () -> ProtobufDecoder.decode(hex("0e00")));
        }

        @Test
        @DisplayName("field number 0 is rejected")
        void zeroFieldNumberIsRejected() {
            assertThrows(ProtobufException.class, () -> ProtobufDecoder.decode(hex("0500000000")));
        }

        @Test
        @DisplayName("nesting past the depth cap stops guessing instead of exhausting the stack")
        void deepNestingHitsTheDepthCap() throws Exception {
            byte[] payload = deeplyNested(60);
            assertTrue(payload.length < 128, "lengths must stay single-byte for this fixture");

            // Decoding must complete. Past the cap the probe gives up and the remaining bytes
            // become a leaf, rather than recursing until the stack dies.
            ProtobufDecoder.Result result = ProtobufDecoder.decode(payload);
            assertFalse(result.value().isEmpty());

            int depth = 0;
            TypeDefinition definition = result.typeDefinition();
            while (definition != null && definition.get(1) != null
                    && definition.get(1).type() == FieldType.MESSAGE) {
                definition = definition.get(1).messageTypeDefinition();
                depth++;
            }
            assertTrue(depth <= 33, "guessing should stop at the depth cap, but went " + depth);
        }

        @Test
        @DisplayName("nesting past the cap still round-trips to the original bytes")
        void deepNestingStillRoundTrips() throws Exception {
            byte[] payload = deeplyNested(60);
            ProtobufDecoder.Result result = ProtobufDecoder.decode(payload);
            assertArrayEquals(payload,
                    ProtobufEncoder.encode(result.value(), result.typeDefinition()));
        }

        @Test
        @DisplayName("one field number carrying two wire types is refused with a clear reason")
        void conflictingWireTypesForOneFieldAreRefused() {
            // field 1 as a varint, then field 1 again as fixed32
            ProtobufException e = assertThrows(ProtobufException.class,
                    () -> ProtobufDecoder.decode(hex("08010d00000000")));
            assertTrue(e.getMessage().contains("two wire types"), e.getMessage());
        }

        /**
         * Wraps a varint in {@code levels} layers of "field 1, wire 2, length n". Each layer adds
         * two bytes, so every length stays below 128 and encodes as a single-byte varint.
         */
        private byte[] deeplyNested(int levels) throws Exception {
            byte[] payload = hex("089601");
            for (int level = 0; level < levels; level++) {
                byte[] wrapped = new byte[payload.length + 2];
                wrapped[0] = 0x0A;
                wrapped[1] = (byte) payload.length;
                System.arraycopy(payload, 0, wrapped, 2, payload.length);
                payload = wrapped;
            }
            return payload;
        }
    }

    @Nested
    class DecodeWithSuppliedDefinition {

        @Test
        @DisplayName("a user's choice of string over message is honoured")
        void suppliedStringTypeWins() throws Exception {
            byte[] data = hex("0a032a0161");
            // Inference guesses message for these bytes.
            assertEquals(FieldType.MESSAGE, ProtobufDecoder.decode(data).typeDefinition().get(1).type());

            TypeDefinition asString = new TypeDefinition();
            asString.define(1, FieldType.STRING);
            MessageValue value = ProtobufDecoder.decodeWith(data, asString);
            assertEquals("*\u0001a", value.get(1).get(0));
        }

        @Test
        @DisplayName("a definition whose wire type contradicts the data is reported clearly")
        void contradictoryDefinitionIsRejected() throws Exception {
            TypeDefinition asString = new TypeDefinition();
            asString.define(1, FieldType.STRING);
            ProtobufException e = assertThrows(ProtobufException.class,
                    () -> ProtobufDecoder.decodeWith(hex("089601"), asString));
            assertTrue(e.getMessage().contains("typed as 'string'"), e.getMessage());
        }

        @Test
        @DisplayName("declaring a non-UTF-8 field as string is reported, suggesting bytes")
        void stringOverInvalidUtf8IsRejected() throws Exception {
            TypeDefinition asString = new TypeDefinition();
            asString.define(1, FieldType.STRING);
            ProtobufException e = assertThrows(ProtobufException.class,
                    () -> ProtobufDecoder.decodeWith(hex("0a02fffe"), asString));
            assertTrue(e.getMessage().contains("bytes"));
        }

        @Test
        @DisplayName("fields absent from the supplied definition are inferred and added to it")
        void unknownFieldsExtendTheDefinition() throws Exception {
            TypeDefinition partial = new TypeDefinition();
            partial.define(1, FieldType.INT);
            MessageValue value = ProtobufDecoder.decodeWith(hex("0896011203616263"), partial);
            assertEquals(List.of(150L), value.get(1));
            assertEquals(FieldType.STRING, partial.get(2).type());
        }

        @Test
        @DisplayName("decoding successive frames through one definition accumulates fields")
        void definitionAccumulatesAcrossFrames() throws Exception {
            TypeDefinition shared = new TypeDefinition();
            ProtobufDecoder.decodeWith(hex("089601"), shared);
            ProtobufDecoder.decodeWith(hex("1203616263"), shared);
            assertEquals(2, shared.size());
            assertEquals(FieldType.INT, shared.get(1).type());
            assertEquals(FieldType.STRING, shared.get(2).type());
        }
    }

    @Nested
    class Utf8Handling {

        @Test
        void strictUtf8RejectsInvalidSequences() {
            assertNull(ProtobufDecoder.tryDecodeUtf8(new byte[]{(byte) 0xFF, (byte) 0xFE}));
            assertNull(ProtobufDecoder.tryDecodeUtf8(new byte[]{(byte) 0xC3})); // truncated 2-byte
        }

        @Test
        void validMultiByteUtf8IsAccepted() {
            assertEquals("héllo", ProtobufDecoder.tryDecodeUtf8("héllo".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8)));
            assertEquals("日本語", ProtobufDecoder.tryDecodeUtf8("日本語".getBytes(
                    java.nio.charset.StandardCharsets.UTF_8)));
        }
    }

    @Test
    @DisplayName("field order on the wire is preserved in both the value and the definition")
    void fieldOrderIsPreserved() throws Exception {
        // fields 3, 1, 2 in that order
        ProtobufDecoder.Result result = ProtobufDecoder.decode(hex("180308011002"));
        assertEquals(List.of(3, 1, 2), List.copyOf(result.value().fields().keySet()));
        assertEquals(List.of(3, 1, 2), List.copyOf(result.typeDefinition().fields().keySet()));
    }

    @Test
    @DisplayName("a definition object passed in is the same one that comes back extended")
    void suppliedDefinitionIsMutatedInPlace() throws Exception {
        TypeDefinition definition = new TypeDefinition();
        ProtobufDecoder.decodeWith(hex("089601"), definition);
        assertSame(definition.get(1), definition.get(1));
        assertEquals(1, definition.size());
    }
}
