package com.nxenon.grpcweb.protobuf;

import java.math.BigInteger;

/**
 * Converts between raw wire values and the typed Java values held in a {@link MessageValue}.
 *
 * <p>Kept separate from the decoder and encoder so both sides of a round-trip use exactly the same
 * interpretation of each {@link FieldType}, and so that interpretation can be tested on its own.
 */
final class ValueCodec {

    /** 2^64, used to map negative longs onto their unsigned equivalents. */
    private static final BigInteger TWO_POW_64 = BigInteger.ONE.shiftLeft(64);

    private static final BigInteger UNSIGNED_64_MAX = TWO_POW_64.subtract(BigInteger.ONE);

    private ValueCodec() {
    }

    // ---------------------------------------------------------------- decode

    static Object varintToValue(long raw, FieldType type) throws ProtobufException {
        return switch (type) {
            case INT -> raw;
            case UINT -> toUnsigned(raw);
            case SINT -> (raw >>> 1) ^ -(raw & 1L);
            case BOOL -> raw != 0;
            default -> throw new ProtobufException(
                    "'" + type.label() + "' is not a varint type");
        };
    }

    static Object fixed32ToValue(int raw, FieldType type) throws ProtobufException {
        return switch (type) {
            case FIXED32 -> raw & 0xFFFFFFFFL;
            case SFIXED32 -> (long) raw;
            case FLOAT -> (double) Float.intBitsToFloat(raw);
            default -> throw new ProtobufException(
                    "'" + type.label() + "' is not a 32-bit fixed type");
        };
    }

    static Object fixed64ToValue(long raw, FieldType type) throws ProtobufException {
        return switch (type) {
            case FIXED64 -> toUnsigned(raw);
            case SFIXED64 -> raw;
            case DOUBLE -> Double.longBitsToDouble(raw);
            default -> throw new ProtobufException(
                    "'" + type.label() + "' is not a 64-bit fixed type");
        };
    }

    /**
     * Renders a 64-bit value as unsigned, using a {@link Long} while it fits and promoting to
     * {@link BigInteger} only past {@link Long#MAX_VALUE}. Keeping small values as longs means the
     * common case produces plain JSON integers rather than anything exotic.
     */
    private static Object toUnsigned(long raw) {
        return raw >= 0 ? (Object) raw : (Object) BigInteger.valueOf(raw).add(TWO_POW_64);
    }

    // ---------------------------------------------------------------- encode

    static long valueToVarint(Object value, FieldType type) throws ProtobufException {
        return switch (type) {
            case INT -> toLong(value, Long.MIN_VALUE, Long.MAX_VALUE, "int");
            case UINT -> toUnsignedLong(value);
            case SINT -> {
                long signed = toLong(value, Long.MIN_VALUE, Long.MAX_VALUE, "sint");
                yield (signed << 1) ^ (signed >> 63);
            }
            case BOOL -> toBoolean(value) ? 1L : 0L;
            default -> throw new ProtobufException("'" + type.label() + "' is not a varint type");
        };
    }

    static int valueToFixed32(Object value, FieldType type) throws ProtobufException {
        return switch (type) {
            case FIXED32 -> (int) toLong(value, 0, 0xFFFFFFFFL, "fixed32");
            case SFIXED32 -> (int) toLong(value, Integer.MIN_VALUE, Integer.MAX_VALUE, "sfixed32");
            case FLOAT -> Float.floatToRawIntBits((float) toDouble(value, "float"));
            default -> throw new ProtobufException(
                    "'" + type.label() + "' is not a 32-bit fixed type");
        };
    }

    static long valueToFixed64(Object value, FieldType type) throws ProtobufException {
        return switch (type) {
            case FIXED64 -> toUnsignedLong(value);
            case SFIXED64 -> toLong(value, Long.MIN_VALUE, Long.MAX_VALUE, "sfixed64");
            case DOUBLE -> Double.doubleToRawLongBits(toDouble(value, "double"));
            default -> throw new ProtobufException(
                    "'" + type.label() + "' is not a 64-bit fixed type");
        };
    }

    // ------------------------------------------------------------ conversion

    private static long toLong(Object value, long min, long max, String typeLabel)
            throws ProtobufException {
        BigInteger big = toBigInteger(value, typeLabel);
        if (big.compareTo(BigInteger.valueOf(min)) < 0 || big.compareTo(BigInteger.valueOf(max)) > 0) {
            throw new ProtobufException(
                    "Value " + big + " is out of range for '" + typeLabel + "'");
        }
        return big.longValue();
    }

    /**
     * Reads a value destined for an unsigned 64-bit field. The result is the two's-complement bit
     * pattern, so values above {@link Long#MAX_VALUE} wrap to a negative long and encode correctly
     * as a varint.
     */
    private static long toUnsignedLong(Object value) throws ProtobufException {
        BigInteger big = toBigInteger(value, "uint");
        if (big.signum() < 0 || big.compareTo(UNSIGNED_64_MAX) > 0) {
            throw new ProtobufException("Value " + big + " is out of range for an unsigned 64-bit field");
        }
        return big.longValue();
    }

    private static BigInteger toBigInteger(Object value, String typeLabel) throws ProtobufException {
        if (value instanceof BigInteger big) {
            return big;
        }
        if (value instanceof Long longValue) {
            return BigInteger.valueOf(longValue);
        }
        if (value instanceof Integer intValue) {
            return BigInteger.valueOf(intValue);
        }
        if (value instanceof Boolean boolValue) {
            return boolValue ? BigInteger.ONE : BigInteger.ZERO;
        }
        if (value instanceof Double doubleValue) {
            if (doubleValue != Math.rint(doubleValue) || doubleValue.isInfinite()) {
                throw new ProtobufException(
                        "Value " + doubleValue + " is not a whole number, so it cannot be a '"
                                + typeLabel + "'");
            }
            return BigInteger.valueOf(doubleValue.longValue());
        }
        if (value instanceof String text) {
            try {
                return new BigInteger(text.trim());
            } catch (NumberFormatException e) {
                throw new ProtobufException(
                        "'" + text + "' is not a number, so it cannot be a '" + typeLabel + "'");
            }
        }
        throw new ProtobufException(
                "Cannot use a " + describe(value) + " as a '" + typeLabel + "'");
    }

    private static double toDouble(Object value, String typeLabel) throws ProtobufException {
        if (value instanceof Double doubleValue) {
            return doubleValue;
        }
        if (value instanceof BigInteger big) {
            return big.doubleValue();
        }
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        if (value instanceof String text) {
            try {
                return Double.parseDouble(text.trim());
            } catch (NumberFormatException e) {
                throw new ProtobufException(
                        "'" + text + "' is not a number, so it cannot be a '" + typeLabel + "'");
            }
        }
        throw new ProtobufException(
                "Cannot use a " + describe(value) + " as a '" + typeLabel + "'");
    }

    private static boolean toBoolean(Object value) throws ProtobufException {
        if (value instanceof Boolean boolValue) {
            return boolValue;
        }
        if (value instanceof Number number) {
            return number.longValue() != 0;
        }
        if (value instanceof String text) {
            String trimmed = text.trim();
            if (trimmed.equalsIgnoreCase("true")) {
                return true;
            }
            if (trimmed.equalsIgnoreCase("false")) {
                return false;
            }
            throw new ProtobufException("'" + text + "' is not a boolean");
        }
        throw new ProtobufException("Cannot use a " + describe(value) + " as a 'bool'");
    }

    private static String describe(Object value) {
        if (value == null) {
            return "null";
        }
        if (value instanceof MessageValue) {
            return "nested message";
        }
        if (value instanceof byte[]) {
            return "byte string";
        }
        return value.getClass().getSimpleName();
    }
}
