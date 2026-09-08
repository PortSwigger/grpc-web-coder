package com.nxenon.grpcweb.protobuf;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class HexTest {

    @Test
    @DisplayName("every byte value survives a hex round-trip")
    void allByteValuesRoundTrip() throws Exception {
        byte[] all = new byte[256];
        for (int i = 0; i < 256; i++) {
            all[i] = (byte) i;
        }
        assertArrayEquals(all, Hex.decode(Hex.encode(all)));
    }

    @Test
    @DisplayName("high bytes encode as two lowercase digits, not as escaped text")
    void highBytesEncodeAsHex() {
        assertEquals("007f80ff", Hex.encode(new byte[]{0x00, 0x7F, (byte) 0x80, (byte) 0xFF}));
    }

    @Test
    void emptyArrayEncodesToEmptyString() throws Exception {
        assertEquals("", Hex.encode(new byte[0]));
        assertArrayEquals(new byte[0], Hex.decode(""));
    }

    @ParameterizedTest
    @DisplayName("hand-edited separators and prefixes are tolerated")
    @ValueSource(strings = {
            "deadbeef",
            "DEADBEEF",
            "de ad be ef",
            "de:ad:be:ef",
            "de-ad-be-ef",
            "de,ad,be,ef",
            "de_ad_be_ef",
            "0xde0xad0xbe0xef",
            "\\xde\\xad\\xbe\\xef",
            "dead\nbeef",
    })
    void toleratesCommonFormatting(String input) throws Exception {
        assertArrayEquals(
                new byte[]{(byte) 0xDE, (byte) 0xAD, (byte) 0xBE, (byte) 0xEF},
                Hex.decode(input));
    }

    @Test
    void oddDigitCountIsRejected() {
        ProtobufException e = assertThrows(ProtobufException.class, () -> Hex.decode("abc"));
        assertEquals(true, e.getMessage().contains("odd number of digits"));
    }

    @Test
    void nonHexCharacterIsRejected() {
        ProtobufException e = assertThrows(ProtobufException.class, () -> Hex.decode("zz"));
        assertEquals(true, e.getMessage().contains("bad character"));
    }
}
