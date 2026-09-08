package com.nxenon.grpcweb.protobuf;

/**
 * Hex conversion for {@code bytes} fields.
 *
 * <p>Byte fields are rendered as hex rather than escaped text so that every byte survives an edit
 * unchanged. This is the single change that fixes the corruption the Jython version suffered, where
 * {@code decode("unicode_escape")} followed by {@code encode("utf-8")} mangled any byte above 0x7F.
 */
public final class Hex {

    private static final char[] DIGITS = "0123456789abcdef".toCharArray();

    private Hex() {
    }

    public static String encode(byte[] bytes) {
        StringBuilder builder = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            builder.append(DIGITS[(b >> 4) & 0x0F]);
            builder.append(DIGITS[b & 0x0F]);
        }
        return builder.toString();
    }

    /**
     * Decodes a hex string, tolerating whitespace, a {@code 0x} prefix and {@code \x} escapes so
     * that hand-edited values and values pasted from other tools both work.
     *
     * @throws ProtobufException if the input is not valid hex, or has an odd number of digits
     */
    public static byte[] decode(String text) throws ProtobufException {
        StringBuilder digits = new StringBuilder(text.length());
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (Character.isWhitespace(c) || c == '_' || c == '-' || c == ':' || c == ',') {
                continue;
            }
            if (c == 'x' || c == 'X') {
                // A 0x or \x marker. 'x' is never a hex digit, so any 'x' is a marker; drop the
                // '0' that a "0x" prefix already contributed.
                if (digits.length() > 0 && digits.charAt(digits.length() - 1) == '0') {
                    digits.deleteCharAt(digits.length() - 1);
                }
                continue;
            }
            if (c == '\\') {
                continue;
            }
            if (Character.digit(c, 16) < 0) {
                throw new ProtobufException(
                        "'" + text + "' is not a valid hex byte string (bad character '" + c + "')");
            }
            digits.append(c);
        }
        if (digits.length() % 2 != 0) {
            throw new ProtobufException(
                    "Hex byte string has an odd number of digits: '" + text + "'");
        }
        byte[] bytes = new byte[digits.length() / 2];
        for (int i = 0; i < bytes.length; i++) {
            int high = Character.digit(digits.charAt(i * 2), 16);
            int low = Character.digit(digits.charAt(i * 2 + 1), 16);
            bytes[i] = (byte) ((high << 4) | low);
        }
        return bytes;
    }
}
