package com.nxenon.grpcweb.protobuf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.math.BigInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the interpretation of raw wire values per field type. These conversions are where a
 * subtle sign or width mistake silently corrupts a payload, so each type is pinned to a known value.
 */
class ValueCodecTest {

    @Nested
    class Varints {

        @Test
        @DisplayName("int keeps the two's-complement value, so -1 stays -1")
        void intIsSigned() throws Exception {
            assertEquals(-1L, ValueCodec.varintToValue(-1L, FieldType.INT));
            assertEquals(300L, ValueCodec.varintToValue(300L, FieldType.INT));
        }

        @Test
        @DisplayName("uint promotes past Long.MAX_VALUE to BigInteger rather than going negative")
        void uintPromotesToBigInteger() throws Exception {
            assertEquals(300L, ValueCodec.varintToValue(300L, FieldType.UINT));
            assertEquals(new BigInteger("18446744073709551615"),
                    ValueCodec.varintToValue(-1L, FieldType.UINT));
        }

        @Test
        @DisplayName("sint applies zig-zag decoding")
        void sintIsZigZag() throws Exception {
            assertEquals(0L, ValueCodec.varintToValue(0L, FieldType.SINT));
            assertEquals(-1L, ValueCodec.varintToValue(1L, FieldType.SINT));
            assertEquals(1L, ValueCodec.varintToValue(2L, FieldType.SINT));
            assertEquals(-2L, ValueCodec.varintToValue(3L, FieldType.SINT));
            assertEquals(2147483647L, ValueCodec.varintToValue(4294967294L, FieldType.SINT));
        }

        @ParameterizedTest
        @ValueSource(longs = {Long.MIN_VALUE, -2, -1, 0, 1, 2, 300, Long.MAX_VALUE})
        @DisplayName("sint zig-zag survives a round-trip for every magnitude")
        void sintRoundTrips(long value) throws Exception {
            long encoded = ValueCodec.valueToVarint(value, FieldType.SINT);
            assertEquals(value, ValueCodec.varintToValue(encoded, FieldType.SINT));
        }

        @Test
        void boolIsAnyNonZero() throws Exception {
            assertEquals(false, ValueCodec.varintToValue(0L, FieldType.BOOL));
            assertEquals(true, ValueCodec.varintToValue(1L, FieldType.BOOL));
            assertEquals(true, ValueCodec.varintToValue(7L, FieldType.BOOL));
        }

        @Test
        @DisplayName("the full unsigned 64-bit range encodes, and one past it does not")
        void uintRangeIsEnforced() throws Exception {
            assertEquals(-1L, ValueCodec.valueToVarint(
                    new BigInteger("18446744073709551615"), FieldType.UINT));
            assertThrows(ProtobufException.class, () -> ValueCodec.valueToVarint(
                    new BigInteger("18446744073709551616"), FieldType.UINT));
            assertThrows(ProtobufException.class,
                    () -> ValueCodec.valueToVarint(BigInteger.valueOf(-1), FieldType.UINT));
        }

        @Test
        void nonVarintTypeIsRejected() {
            assertThrows(ProtobufException.class,
                    () -> ValueCodec.varintToValue(1L, FieldType.STRING));
        }
    }

    @Nested
    class Fixed32 {

        @Test
        @DisplayName("fixed32 is unsigned and sfixed32 is signed for the same bits")
        void signedness() throws Exception {
            assertEquals(4294967295L, ValueCodec.fixed32ToValue(-1, FieldType.FIXED32));
            assertEquals(-1L, ValueCodec.fixed32ToValue(-1, FieldType.SFIXED32));
            assertEquals(20L, ValueCodec.fixed32ToValue(20, FieldType.FIXED32));
        }

        @Test
        void floatReadsIeeeBits() throws Exception {
            int bits = Float.floatToRawIntBits(3.14f);
            assertEquals((double) 3.14f, ValueCodec.fixed32ToValue(bits, FieldType.FLOAT));
        }

        @Test
        void floatRoundTripsExactly() throws Exception {
            int bits = Float.floatToRawIntBits(3.14f);
            Object decoded = ValueCodec.fixed32ToValue(bits, FieldType.FLOAT);
            assertEquals(bits, ValueCodec.valueToFixed32(decoded, FieldType.FLOAT));
        }

        @Test
        void outOfRangeValuesAreRejected() {
            assertThrows(ProtobufException.class,
                    () -> ValueCodec.valueToFixed32(4294967296L, FieldType.FIXED32));
            assertThrows(ProtobufException.class,
                    () -> ValueCodec.valueToFixed32(-1L, FieldType.FIXED32));
            assertThrows(ProtobufException.class,
                    () -> ValueCodec.valueToFixed32(Integer.MAX_VALUE + 1L, FieldType.SFIXED32));
        }
    }

    @Nested
    class Fixed64 {

        @Test
        void signedness() throws Exception {
            assertEquals(new BigInteger("18446744073709551615"),
                    ValueCodec.fixed64ToValue(-1L, FieldType.FIXED64));
            assertEquals(-1L, ValueCodec.fixed64ToValue(-1L, FieldType.SFIXED64));
        }

        @Test
        void doubleReadsIeeeBits() throws Exception {
            long bits = Double.doubleToRawLongBits(4.55);
            assertEquals(4.55, ValueCodec.fixed64ToValue(bits, FieldType.DOUBLE));
            assertEquals(bits, ValueCodec.valueToFixed64(4.55, FieldType.DOUBLE));
        }
    }

    @Nested
    class Coercion {

        @Test
        @DisplayName("a numeric string is accepted, since edited JSON often quotes numbers")
        void numericStringsAreAccepted() throws Exception {
            assertEquals(42L, ValueCodec.valueToVarint("42", FieldType.INT));
            assertEquals(42L, ValueCodec.valueToVarint(" 42 ", FieldType.INT));
        }

        @Test
        void nonNumericStringIsRejectedWithAReadableMessage() {
            ProtobufException e = assertThrows(ProtobufException.class,
                    () -> ValueCodec.valueToVarint("abc", FieldType.INT));
            assertTrue(e.getMessage().contains("not a number"));
        }

        @Test
        @DisplayName("a fractional value cannot become an integer field")
        void fractionalValueRejectedForIntegerField() {
            ProtobufException e = assertThrows(ProtobufException.class,
                    () -> ValueCodec.valueToVarint(1.5, FieldType.INT));
            assertTrue(e.getMessage().contains("not a whole number"));
        }

        @Test
        void wholeDoubleIsAcceptedForIntegerField() throws Exception {
            assertEquals(7L, ValueCodec.valueToVarint(7.0, FieldType.INT));
        }

        @Test
        void booleanStringsAreAccepted() throws Exception {
            assertEquals(1L, ValueCodec.valueToVarint("true", FieldType.BOOL));
            assertEquals(0L, ValueCodec.valueToVarint("FALSE", FieldType.BOOL));
            assertThrows(ProtobufException.class,
                    () -> ValueCodec.valueToVarint("maybe", FieldType.BOOL));
        }

        @Test
        @DisplayName("a nested message cannot be used where a scalar is expected")
        void messageValueRejectedForScalar() {
            ProtobufException e = assertThrows(ProtobufException.class,
                    () -> ValueCodec.valueToVarint(new MessageValue(), FieldType.INT));
            assertTrue(e.getMessage().contains("nested message"));
        }
    }
}
