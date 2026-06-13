#!/usr/bin/env python3
"""
Standalone gRPC-Web / protobuf decoder & encoder.

Unlike grpc_coder.py (which only strips the gRPC frame + base64 and then relies
on the external `protoscope` binary to parse the protobuf), this tool decodes
AND encodes the raw protobuf *wire format* itself in pure Python -- no
dependencies, no protoscope required.

It is field-number based: protobuf wire bytes do NOT contain field names, so the
output shows field numbers, inferred wire types and best-effort values (handy for
pentesting / payload manipulation when you don't have the .proto file).

Usage:
    # decode (default)
    echo '<payload>' | python3 grpc_protobuf_decoder.py [--type TYPE] [--file FILE]
    # encode (text -> protobuf)
    echo '<text>'    | python3 grpc_protobuf_decoder.py --encode [--type TYPE]

    --decode  decode a payload into readable text (default)
    --encode  encode the readable text format back into a protobuf payload
    --type    payload format. one of:
               grpc-web-text  (base64, default) -- e.g. AAAAAAUKA2Zvbw==
               grpc-web+proto (raw gRPC framed bytes)
               raw            (raw protobuf bytes, no gRPC frame)
               hex            (hex string of raw protobuf bytes, e.g. 0a03666f6f)
    --file    read input from a file instead of stdin
    --help    print this help

Text format (what --decode prints and --encode consumes):
    1: "a string"            # length-delimited string
    2: 150                   # varint (leading integer is used; hints ignored)
    3: 0xdeadbeef            # raw bytes (hex)
    4: fixed64=123           # 8-byte fixed (wire type 1)
    5: fixed32=42            # 4-byte fixed (wire type 5)
    6: {                     # nested message
      1: "nested"
    }

Examples:
    echo 'AAAAAAUKA2Zvbw==' | python3 grpc_protobuf_decoder.py
    echo '0a03666f6f1001'   | python3 grpc_protobuf_decoder.py --type hex
    printf '1: "foo"\n2: 150\n' | python3 grpc_protobuf_decoder.py --encode --type hex
"""

import base64
import struct
import sys
from argparse import ArgumentParser

WIRE_VARINT = 0
WIRE_64BIT = 1
WIRE_LEN = 2
WIRE_GROUP_START = 3
WIRE_GROUP_END = 4
WIRE_32BIT = 5

WIRE_NAMES = {
    WIRE_VARINT: "varint",
    WIRE_64BIT: "64-bit",
    WIRE_LEN: "len",
    WIRE_GROUP_START: "group-start",
    WIRE_GROUP_END: "group-end",
    WIRE_32BIT: "32-bit",
}


def read_varint(buf, pos):
    """Read a base-128 varint starting at pos. Returns (value, new_pos)."""
    result = 0
    shift = 0
    while True:
        if pos >= len(buf):
            raise ValueError("truncated varint")
        b = buf[pos]
        result |= (b & 0x7F) << shift
        pos += 1
        if not (b & 0x80):
            break
        shift += 7
        if shift > 70:
            raise ValueError("varint too long")
    return result, pos


def zigzag_decode(n):
    """Decode a zig-zag encoded signed varint (sint32/sint64)."""
    return (n >> 1) ^ -(n & 1)


def is_probably_text(data):
    """Heuristic: does `data` look like a printable UTF-8 string?"""
    if not data:
        return False
    try:
        s = data.decode("utf-8")
    except UnicodeDecodeError:
        return False
    # reject if it contains control chars (except common whitespace)
    for ch in s:
        if ord(ch) < 0x20 and ch not in "\t\n\r":
            return False
    return True


def try_parse_message(data):
    """
    Attempt to parse `data` as a protobuf message.
    Returns a list of fields if it parses cleanly and consumes all bytes,
    otherwise returns None.
    """
    try:
        fields = parse_fields(data)
    except (ValueError, IndexError, struct.error):
        return None
    return fields


def parse_fields(buf):
    """Parse a protobuf message body into a list of field dicts."""
    fields = []
    pos = 0
    n = len(buf)
    while pos < n:
        tag, pos = read_varint(buf, pos)
        field_number = tag >> 3
        wire_type = tag & 0x07
        if field_number == 0:
            raise ValueError("invalid field number 0")

        if wire_type == WIRE_VARINT:
            value, pos = read_varint(buf, pos)
            fields.append({"field": field_number, "wire": wire_type, "value": value})

        elif wire_type == WIRE_64BIT:
            if pos + 8 > n:
                raise ValueError("truncated 64-bit")
            raw = buf[pos:pos + 8]
            pos += 8
            fields.append({"field": field_number, "wire": wire_type, "value": raw})

        elif wire_type == WIRE_LEN:
            length, pos = read_varint(buf, pos)
            if pos + length > n:
                raise ValueError("truncated length-delimited")
            raw = buf[pos:pos + length]
            pos += length
            fields.append({"field": field_number, "wire": wire_type, "value": raw})

        elif wire_type == WIRE_32BIT:
            if pos + 4 > n:
                raise ValueError("truncated 32-bit")
            raw = buf[pos:pos + 4]
            pos += 4
            fields.append({"field": field_number, "wire": wire_type, "value": raw})

        elif wire_type == WIRE_GROUP_START:
            # groups are deprecated; consume until matching group-end
            inner_start = pos
            depth = 1
            while depth > 0 and pos < n:
                t, pos = read_varint(buf, pos)
                wt = t & 0x07
                if wt == WIRE_GROUP_START:
                    depth += 1
                elif wt == WIRE_GROUP_END:
                    depth -= 1
                elif wt == WIRE_VARINT:
                    _, pos = read_varint(buf, pos)
                elif wt == WIRE_64BIT:
                    pos += 8
                elif wt == WIRE_LEN:
                    ln, pos = read_varint(buf, pos)
                    pos += ln
                elif wt == WIRE_32BIT:
                    pos += 4
            raw = buf[inner_start:pos]
            fields.append({"field": field_number, "wire": wire_type, "value": raw})

        elif wire_type == WIRE_GROUP_END:
            raise ValueError("unexpected group-end")

        else:
            raise ValueError("unknown wire type %d" % wire_type)

    return fields


def render_varint(value):
    """Show the multiple plausible interpretations of a varint."""
    interps = ["%d" % value]
    zz = zigzag_decode(value)
    if zz != value:
        interps.append("zigzag=%d" % zz)
    if value in (0, 1):
        interps.append("bool=%s" % ("true" if value else "false"))
    return ", ".join(interps)


def render_64bit(raw):
    u = struct.unpack("<Q", raw)[0]
    i = struct.unpack("<q", raw)[0]
    d = struct.unpack("<d", raw)[0]
    parts = ["fixed64=%d" % u]
    if i != u:
        parts.append("sfixed64=%d" % i)
    parts.append("double=%g" % d)
    return ", ".join(parts)


def render_32bit(raw):
    u = struct.unpack("<I", raw)[0]
    i = struct.unpack("<i", raw)[0]
    f = struct.unpack("<f", raw)[0]
    parts = ["fixed32=%d" % u]
    if i != u:
        parts.append("sfixed32=%d" % i)
    parts.append("float=%g" % f)
    return ", ".join(parts)


def format_fields(fields, indent=0):
    """Render parsed fields into a protoscope-like indented string."""
    pad = "  " * indent
    lines = []
    for f in fields:
        fn = f["field"]
        wt = f["wire"]
        wname = WIRE_NAMES.get(wt, "?")

        if wt == WIRE_VARINT:
            lines.append("%s%d: %s" % (pad, fn, render_varint(f["value"])))

        elif wt == WIRE_64BIT:
            lines.append("%s%d: %s" % (pad, fn, render_64bit(f["value"])))

        elif wt == WIRE_32BIT:
            lines.append("%s%d: %s" % (pad, fn, render_32bit(f["value"])))

        elif wt in (WIRE_LEN, WIRE_GROUP_START):
            raw = f["value"]
            nested = try_parse_message(raw) if raw else []
            # Prefer text if it cleanly decodes as a printable string.
            if is_probably_text(raw) and not (nested and len(raw) > 0 and not is_probably_text(raw)):
                lines.append('%s%d: "%s"' % (pad, fn, raw.decode("utf-8")))
            elif nested:
                lines.append("%s%d: {" % (pad, fn))
                lines.append(format_fields(nested, indent + 1))
                lines.append("%s}" % pad)
            elif is_probably_text(raw):
                lines.append('%s%d: "%s"' % (pad, fn, raw.decode("utf-8")))
            else:
                lines.append("%s%d: bytes(%d) 0x%s" % (pad, fn, len(raw), raw.hex()))

    return "\n".join(l for l in lines if l != "")


# --------------------------------------------------------------------------- #
# Encoding: readable text format -> protobuf wire bytes
# --------------------------------------------------------------------------- #

def encode_varint(n):
    """Encode an integer as a base-128 varint (negatives -> unsigned 64-bit)."""
    if n < 0:
        n &= (1 << 64) - 1
    out = bytearray()
    while True:
        b = n & 0x7F
        n >>= 7
        if n:
            out.append(b | 0x80)
        else:
            out.append(b)
            break
    return bytes(out)


def encode_tag(field_number, wire_type):
    return encode_varint((field_number << 3) | wire_type)


def parse_quoted(s):
    """Parse a double-quoted string (with \\n \\r \\t \\xNN \\\\ \\\" escapes)."""
    if not s.startswith('"'):
        raise ValueError("expected quoted string: %r" % s)
    out = []
    i = 1
    while i < len(s):
        c = s[i]
        if c == "\\" and i + 1 < len(s):
            nxt = s[i + 1]
            if nxt == "n":
                out.append("\n"); i += 2
            elif nxt == "r":
                out.append("\r"); i += 2
            elif nxt == "t":
                out.append("\t"); i += 2
            elif nxt == "x":
                out.append(chr(int(s[i + 2:i + 4], 16))); i += 4
            else:
                out.append(nxt); i += 2
        elif c == '"':
            break
        else:
            out.append(c); i += 1
    return "".join(out)


def _int_after(s, marker):
    tail = s.split(marker, 1)[1].strip()
    token = tail.split(",")[0].strip().split()[0]
    return int(token)


def parse_scalar(field_number, rest):
    """Parse one non-nested value line into a (kind, field, value) tuple."""
    if rest.startswith('"'):
        return ("str", field_number, parse_quoted(rest))
    if "fixed64=" in rest:
        return ("fixed64", field_number, _int_after(rest, "fixed64="))
    if "fixed32=" in rest:
        return ("fixed32", field_number, _int_after(rest, "fixed32="))
    if "0x" in rest:
        hexpart = rest.split("0x", 1)[1].strip().split()[0]
        return ("bytes", field_number, bytes.fromhex(hexpart))
    # otherwise: leading integer (ignore trailing hints like ", zigzag=..")
    token = rest.split(",")[0].strip().split()[0]
    return ("varint", field_number, int(token))


def parse_text_to_fields(text):
    """Parse the readable text format into a nested list of field tuples."""
    root = []
    stack = [root]
    open_fields = []
    for raw_line in text.split("\n"):
        line = raw_line.strip()
        if not line or line.startswith("#"):
            continue
        if line == "}":
            if not open_fields:
                raise ValueError("unbalanced '}'")
            fields = stack.pop()
            fn = open_fields.pop()
            stack[-1].append(("msg", fn, fields))
            continue
        if ":" not in line:
            raise ValueError("bad line (no field number): %r" % raw_line)
        head, rest = line.split(":", 1)
        field_number = int(head.strip())
        rest = rest.strip()
        if rest.startswith("{"):
            stack.append([])
            open_fields.append(field_number)
            continue
        stack[-1].append(parse_scalar(field_number, rest))
    if len(stack) != 1:
        raise ValueError("unbalanced braces (missing '}')")
    return root


def serialize_fields(fields):
    """Serialize a nested list of field tuples into protobuf wire bytes."""
    out = bytearray()
    for kind, fn, value in fields:
        if kind == "varint":
            out += encode_tag(fn, WIRE_VARINT) + encode_varint(value)
        elif kind == "str":
            data = value.encode("utf-8")
            out += encode_tag(fn, WIRE_LEN) + encode_varint(len(data)) + data
        elif kind == "bytes":
            out += encode_tag(fn, WIRE_LEN) + encode_varint(len(value)) + value
        elif kind == "msg":
            data = serialize_fields(value)
            out += encode_tag(fn, WIRE_LEN) + encode_varint(len(data)) + data
        elif kind == "fixed64":
            packed = struct.pack("<q" if value < 0 else "<Q", value)
            out += encode_tag(fn, WIRE_64BIT) + packed
        elif kind == "fixed32":
            packed = struct.pack("<i" if value < 0 else "<I", value)
            out += encode_tag(fn, WIRE_32BIT) + packed
        else:
            raise ValueError("unknown field kind: %s" % kind)
    return bytes(out)


def add_grpc_frame(message):
    """Prepend the 5-byte gRPC frame (uncompressed)."""
    return b"\x00" + struct.pack(">I", len(message)) + message


def encode(content, ctype):
    text = content.decode("utf-8") if isinstance(content, bytes) else content
    message = serialize_fields(parse_text_to_fields(text))

    if ctype == "raw":
        sys.stdout.buffer.write(message)
    elif ctype == "hex":
        print(message.hex())
    elif ctype == "grpc-web+proto":
        sys.stdout.buffer.write(add_grpc_frame(message))
    elif ctype == "grpc-web-text":
        print(base64.b64encode(add_grpc_frame(message)).decode())
    else:
        raise ValueError("unknown type: %s" % ctype)


# --------------------------------------------------------------------------- #
# gRPC framing helpers (decode side)
# --------------------------------------------------------------------------- #

def strip_grpc_frame(data):
    """
    Strip the 5-byte gRPC message frame: 1 byte compression flag +
    4 bytes big-endian length. Returns the message bytes.
    """
    if len(data) < 5:
        raise ValueError("payload too short to contain a gRPC frame")
    compressed = data[0]
    if compressed not in (0, 1):
        raise ValueError(
            "first frame byte is 0x%02x (expected 0x00/0x01). "
            "Maybe this is raw protobuf? try --type raw" % compressed
        )
    if compressed == 1:
        sys.stderr.write("warning: payload is marked COMPRESSED; "
                         "decoding will likely fail without decompression\n")
    length = struct.unpack(">I", data[1:5])[0]
    body = data[5:5 + length]
    return body


def get_message_bytes(content, ctype):
    """Normalize input into raw protobuf message bytes based on content type."""
    if ctype == "grpc-web-text":
        # base64 (may include trailing whitespace/newlines)
        b64 = content.strip() if isinstance(content, bytes) else content.strip().encode()
        decoded = base64.b64decode(b64)
        return strip_grpc_frame(decoded)
    elif ctype == "grpc-web+proto":
        return strip_grpc_frame(content)
    elif ctype == "raw":
        return content
    elif ctype == "hex":
        h = content.strip() if isinstance(content, bytes) else content.strip().encode()
        h = h.replace(b" ", b"").replace(b"\n", b"")
        # allow optional \x and 0x prefixes
        h = h.replace(b"\\x", b"").replace(b"0x", b"")
        return bytes.fromhex(h.decode())
    else:
        raise ValueError("unknown type: %s" % ctype)


def decode(content, ctype):
    body = get_message_bytes(content, ctype)
    if not body:
        print("(empty message)")
        return
    fields = try_parse_message(body)
    if fields is None:
        sys.stderr.write("error: could not parse protobuf wire format. "
                         "raw bytes (hex):\n")
        print(body.hex())
        return
    print(format_fields(fields))


def main():
    parser = ArgumentParser(add_help=False)
    parser.add_argument("--help", action="store_true", default=False)
    parser.add_argument("--encode", action="store_true")
    parser.add_argument("--decode", action="store_true")
    parser.add_argument("--type", default="grpc-web-text")
    parser.add_argument("--file", default=None)
    args, _ = parser.parse_known_args()

    if args.help:
        print(__doc__)
        return

    if args.file is None:
        content = sys.stdin.buffer.read()
    else:
        with open(args.file, "rb") as fh:
            content = fh.read()

    try:
        if args.encode:
            encode(content, args.type)
        else:
            decode(content, args.type)
    except Exception as e:
        action = "encode" if args.encode else "decode"
        sys.stderr.write("%s failed: %s\n" % (action, e))
        sys.exit(1)


if __name__ == "__main__":
    main()
