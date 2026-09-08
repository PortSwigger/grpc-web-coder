package com.nxenon.grpcweb.protobuf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FieldTypeTest {

    @ParameterizedTest
    @EnumSource(FieldType.class)
    @DisplayName("every type's label parses back to that type")
    void labelsRoundTrip(FieldType type) throws Exception {
        assertSame(type, FieldType.fromLabel(type.label()));
    }

    @ParameterizedTest
    @CsvSource({
            "int32,INT", "int64,INT", "uint32,UINT", "uint64,UINT",
            "sint32,SINT", "sint64,SINT", "boolean,BOOL",
            "str,STRING", "byte,BYTES", "msg,MESSAGE",
    })
    @DisplayName("proto-style aliases resolve to the right type")
    void aliasesResolve(String label, FieldType expected) throws Exception {
        assertSame(expected, FieldType.fromLabel(label));
    }

    @ParameterizedTest
    @ValueSource(strings = {"INT", " int ", "Int", "STRING"})
    @DisplayName("labels are case- and whitespace-insensitive")
    void labelsAreLenient(String label) throws Exception {
        assertTrue(FieldType.fromLabel(label) == FieldType.INT
                || FieldType.fromLabel(label) == FieldType.STRING);
    }

    @Test
    void unknownLabelIsRejected() {
        ProtobufException e =
                assertThrows(ProtobufException.class, () -> FieldType.fromLabel("decimal"));
        assertTrue(e.getMessage().contains("Unknown field type"));
    }

    @Test
    void nullLabelIsRejected() {
        assertThrows(ProtobufException.class, () -> FieldType.fromLabel(null));
    }

    @Test
    @DisplayName("each type maps to the wire type it must be encoded as")
    void wireTypeMapping() {
        assertEquals(WireType.VARINT, FieldType.INT.wireType());
        assertEquals(WireType.VARINT, FieldType.BOOL.wireType());
        assertEquals(WireType.VARINT, FieldType.SINT.wireType());
        assertEquals(WireType.FIXED32, FieldType.FLOAT.wireType());
        assertEquals(WireType.FIXED64, FieldType.DOUBLE.wireType());
        assertEquals(WireType.LENGTH_DELIMITED, FieldType.STRING.wireType());
        assertEquals(WireType.LENGTH_DELIMITED, FieldType.BYTES.wireType());
        assertEquals(WireType.LENGTH_DELIMITED, FieldType.MESSAGE.wireType());
        assertEquals(WireType.START_GROUP, FieldType.GROUP.wireType());
    }

    @Test
    void onlyMessageAndGroupAreMessageLike() {
        for (FieldType type : FieldType.values()) {
            boolean expected = type == FieldType.MESSAGE || type == FieldType.GROUP;
            assertEquals(expected, type.isMessageLike(), type.label());
        }
    }

    @Test
    @DisplayName("wire types 6 and 7 are rejected as reserved")
    void reservedWireTypesRejected() {
        assertThrows(ProtobufException.class, () -> WireType.fromTag(0x0E)); // field 1, wire 6
        assertThrows(ProtobufException.class, () -> WireType.fromTag(0x0F)); // field 1, wire 7
    }
}
