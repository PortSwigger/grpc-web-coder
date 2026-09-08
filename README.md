# gRPC Pentest Suite

Tools for pentesting gRPC-Web applications.

Supported content types: `application/grpc-web-text` and `application/grpc-web+proto`.

## The Burp extension lives in its own repository now

The Burp extension was rewritten in Java and moved out of this repo:

- **Repository:** [nxenon/grpc-web-coder-extension](https://github.com/nxenon/grpc-web-coder-extension)
- **BApp Store:** [gRPC-Web Coder](https://portswigger.net/bappstore/63b92be302fa4521bf18d74b3adbbc00)

[![gRPC-Web Coder BApp](https://github.com/user-attachments/assets/5732481b-8126-49cc-9fd5-35c86248db75)](https://portswigger.net/bappstore/63b92be302fa4521bf18d74b3adbbc00)

Install it from the BApp Store and you are done. It needs **no Jython, no protoscope and no
`pip install`** — the old Python extension needed all three.

It also does things the Python extension could not: decode responses as well as requests, handle
every frame of a server-streaming body plus the trailer frame, inflate gzip frames, and export the
JavaScript analysis as JSON, CSV, a reconstructed `.proto` or type definitions.

## Looking for the old Python files?

They are on the **[`legacy/python-tools`](../../tree/legacy/python-tools)** branch, frozen and
unmaintained:

```bash
git checkout legacy/python-tools
```

That branch holds the complete suite as it stood before the rewrite, including both Jython Burp
extensions (`grpc_web_burp_extension.py` and `old_grpc_web_burp_extension_with_dependency.py`),
their `burp_utils/` support code, and the vendored `libs/` (blackboxprotobuf, six).

One thing only the old extension had: the **Big String Chunker integrated into Burp**. The Java
extension does not provide it yet, so use [`big_string_chunker.py`](big_string_chunker.py) from the
command line, or the legacy branch if you need it inside Burp.

## What is in this repository

Four standalone command-line tools. None of them is replaced by the Burp extension.

| Tool | What it does | Requirements |
| --- | --- | --- |
| [grpc_protobuf_decoder.py](#standalone-protobuf-decoder-and-encoder) | Decodes and encodes the protobuf wire format directly. Handles grpc-web-text, grpc-web+proto, raw protobuf and hex. | none |
| [grpc_coder.py](#grpc-coder) | Encodes and decodes gRPC-Web payloads via protoscope. | protoscope |
| [grpc_scan.py](#grpc-scan) | Pulls endpoints, messages and field types out of webpacked gRPC-Web JavaScript. | `pip install -r requirements.txt` |
| [big_string_chunker.py](#big-string-chunker) | Splits a long string into 80-character protobuf pieces, and reassembles it. | none |

If you are choosing between the two coders, use **`grpc_protobuf_decoder.py`**: it does everything
`grpc_coder.py` does, plus raw and hex payloads, and it needs nothing installed.

---

# Standalone Protobuf Decoder and Encoder

[grpc_protobuf_decoder.py](grpc_protobuf_decoder.py) decodes **and** encodes the protobuf wire
format itself, with no dependencies and without protoscope.

Use it when you do not want to install protoscope, or when the payload is plain protobuf that is not
wrapped in a gRPC-Web frame — for example a protobuf message taken from a Kafka topic, a file, or a
`.bin` dump.

> Protobuf wire bytes do not carry field names, so output is field-number based. Run
> [grpc_scan.py](#grpc-scan) over the site's JavaScript, or the Burp extension's analyzer, to
> recover the names.

## Options

```
python3 grpc_protobuf_decoder.py --help

--decode  decode a payload into readable text (default)
--encode  encode the readable text format back into a protobuf payload
--type    payload format:
            grpc-web-text   base64, default        e.g. AAAAAAUKA2Zvbw==
            grpc-web+proto  raw gRPC framed bytes
            raw             raw protobuf bytes (no gRPC frame)
            hex             hex string of raw protobuf bytes
--file    read input from a file instead of stdin
```

The readable text format, printed by `--decode` and consumed by `--encode`:

```
1: "a string"        # length-delimited string
2: 150               # varint
3: 0xdeadbeef        # raw bytes (hex)
4: fixed64=123       # 8-byte fixed (wire type 1)
5: fixed32=42        # 4-byte fixed (wire type 5)
6: {                 # nested message
  1: "nested"
}
```

## Decoding a gRPC-Web payload

```bash
echo "AAAAABYSC0FtaW4gTmFzaXJpGDY6BVhlbm9u" | python3 grpc_protobuf_decoder.py --decode
```

```
2: "Amin Nasiri"
3: 54, zigzag=27
7: "Xenon"
```

Varints show alternative interpretations, because the wire type alone is ambiguous. On re-encode only
the leading integer is used and the hints are ignored, so leave them or delete them as you like.

## Kafka protobuf messages

A protobuf message in a Kafka topic is raw protobuf bytes with no gRPC framing, so use `--type raw`
for a binary file or `--type hex` for a hex string.

```bash
# message.bin holds the raw protobuf bytes of a Kafka record value
python3 grpc_protobuf_decoder.py --decode --type raw --file message.bin

# or as a hex string
echo '0a03666f6f1096011a070a036261721001' | python3 grpc_protobuf_decoder.py --decode --type hex
```

```
1: "foo"
2: 150, zigzag=75
3: {
  1: "bar"
  2: 1, zigzag=-1, bool=true
}
```

Edit the fields, then encode back to raw bytes you can publish:

```bash
printf '1: "foo INJECTED"\n2: 9999\n3: {\n  1: "bar"\n  2: 1\n}\n' \
    | python3 grpc_protobuf_decoder.py --encode --type raw > new_message.bin

# or a hex string instead of a binary file
printf '1: "foo INJECTED"\n2: 9999\n' \
    | python3 grpc_protobuf_decoder.py --encode --type hex
```

To turn the same edited text back into a gRPC-Web payload, drop `--type` (it defaults to
`grpc-web-text`):

```bash
printf '2: "Amin Nasiri Xenon GRPC"\n3: 54\n7: "<script>alert(origin)</script>"\n' \
    | python3 grpc_protobuf_decoder.py --encode
```

> **Confluent Schema Registry:** if the producer uses the Confluent wire format, each value has a
> 1-byte magic (`0x00`), a 4-byte schema id and a protobuf message-index header *before* the
> protobuf bytes. Strip that prefix before decoding with `--type raw`, and re-add it after encoding.

---

# gRPC Coder

[grpc_coder.py](grpc_coder.py) pipes gRPC-Web payloads through
[protoscope](https://github.com/protocolbuffers/protoscope).

> Prefer [grpc_protobuf_decoder.py](#standalone-protobuf-decoder-and-encoder) unless you specifically
> want protoscope's output format. This tool needs protoscope installed globally.

```bash
go install github.com/protocolbuffers/protoscope/cmd/protoscope...@latest
```

```
echo payload | python3 grpc_coder.py [--encode OR --decode]

General Arguments:
  --encode       encode protoscope binary output to application/grpc-web-text
  --decode       decode application/grpc-web-text base64 encoded payload to protoscope format
  --type         content-type of payload [default: grpc-web-text]
                 available types: [grpc-web-text, grpc-web+proto]

Input Arguments:
Default Input is Standard Input
  --file        to get input from a file

Help:
  --help        print help message
```

## Decoding

```bash
echo "AAAAABYSC0FtaW4gTmFzaXJpGDY6BVhlbm9u" \
    | python3 grpc_coder.py --decode --type grpc-web-text | protoscope > out.txt
cat out.txt
```

```
2: {"Amin Nasiri"}
3: 54
7: {"Xenon"}
```

Edit `out.txt`:

```
2: {"Amin Nasiri Xenon GRPC"}
3: 54
7: {"<script>alert(origin)</script>"}
```

## Encoding

```bash
protoscope -s out.txt | python3 grpc_coder.py --encode --type grpc-web-text
```

```
AAAAADoSFkFtaW4gTmFzaXJpIFhlbm9uIEdSUEMYNjoePHNjcmlwdD5hbGVydChvcmlnaW4pPC9zY3JpcHQ+
```

Put the new base64 payload back into the intercepted request.

---

# gRPC Scan

[grpc_scan.py](grpc_scan.py) extracts endpoints, messages and field types from gRPC-Web JavaScript.

```bash
pip install -r requirements.txt

python3 grpc_scan.py --file main.js
# or
cat main.js | python3 grpc_scan.py --stdin
```

## What it expects

This tool is written for **webpacked** bundles. Its endpoint pattern requires `MethodDescriptor("`
with a double quote directly against the path, which is what a bundler produces. Raw
`protoc-gen-grpc-web` output uses single quotes with the path on its own line:

```js
const methodDescriptor_AuthService_Login = new grpc.web.MethodDescriptor(
  '/auth.AuthService/Login',
```

and **no endpoints will be found** in a file shaped like that. Messages are still extracted either
way.

If that bites you, use the Burp extension's **Analyze gRPC-Web Endpoints** instead. It accepts
either quote style and any whitespace, also picks up paths from `rpcCall` sites, recovers each
method's call style and request/response message types, converts accessor names back to real proto
field names (`user_name` rather than `UserName`), and exports the result as JSON, CSV or a `.proto`.

## Saving JavaScript files

Open the file in a browser and save it, or download it directly. **Do not** copy and paste the
JavaScript content.

Protobuf version support: version 3 is fine; some version 2 features do not work.

## Options

```
python3 grpc_scan.py [INPUT]
Input Arguments:
  --file      file name of js file
  --stdin     get input from standard input
Help:
  --help      print help message
```

## Example output

```
python3 grpc_scan.py --file main.js

Found Endpoints:
  /grpc.gateway.testing.EchoService/Echo
  /grpc.gateway.testing.EchoService/EchoAbort
  /grpc.gateway.testing.EchoService/NoOp
  /grpc.gateway.testing.EchoService/ServerStreamingEcho
  /grpc.gateway.testing.EchoService/ServerStreamingEchoAbort

Found Messages:

grpc.gateway.testing.EchoRequest:
+------------+--------------------+--------------+
| Field Name |     Field Type     | Field Number |
+============+====================+==============+
| Message    | Proto3StringField  | 1            |
+------------+--------------------+--------------+
| Name       | Proto3StringField  | 2            |
+------------+--------------------+--------------+
| Age        | Proto3IntField     | 3            |
+------------+--------------------+--------------+
| IsAdmin    | Proto3BooleanField | 4            |
+------------+--------------------+--------------+
| Weight     | Proto3FloatField   | 5            |
+------------+--------------------+--------------+

grpc.gateway.testing.ServerStreamingEchoRequest:
+-----------------+-------------------+--------------+
|   Field Name    |    Field Type     | Field Number |
+=================+===================+==============+
| Message         | Proto3StringField | 1            |
+-----------------+-------------------+--------------+
| MessageCount    | Proto3IntField    | 2            |
+-----------------+-------------------+--------------+
| MessageInterval | Proto3IntField    | 3            |
+-----------------+-------------------+--------------+
```

---

# Big String Chunker

A long string cannot sit on one line in protoscope's text format, so
[big_string_chunker.py](big_string_chunker.py) splits it into 80-character pieces, and reassembles
them.

```bash
# chunk
cat bigString.txt | python3 big_string_chunker.py --stdin --chunk
python3 big_string_chunker.py --file bigString.txt --chunk

# un-chunk
cat chunkedString.txt | python3 big_string_chunker.py --stdin --un-chunk
python3 big_string_chunker.py --file chunkedString.txt --un-chunk
```

Given a long base64 blob, it produces:

```
1: {
  "T2dnUwACAAAAAAAAAABzFQAAAAAAAAAJCzcBE09wdXNIZWFkAQE4AYC7AAAAAABPZ2dTAAAAAAAAAAAA"
  "AHMVAAABAAAAo2rOoQE3T3B1c1RhZ3MPAAAAbGlib3B1cyB1bmtub3duAQAAABQAAABFTkNPREVSPU1v"
  "emlsbGExMjQuME9nZ1MAAMAwAAAAAAAAcxUAAAIAAAD1DNygG//T/yb/KP//CP8h/yT/JP8k/yX/JP8l"
  "YhAygIak+pZZu654kaBYG+9Hag=="
}
```

> The tool uses field number 1 by default — change it to the field you are actually targeting.

The old Burp extension had this built in. The Java extension does not, so run it from the command
line, or see the [`legacy/python-tools`](../../tree/legacy/python-tools) branch.

---

# Articles and videos

The methodology for pentesting gRPC-Web, including finding hidden services and endpoints:

- [Hacking into gRPC-Web](https://infosecwriteups.com/hacking-into-grpc-web-a54053757a45)
- [Hacking into gRPC-Web: Part 2](https://medium.com/@nxenon/hacking-into-grpc-web-part-2-f8540309e1e8)
  — covers `application/grpc-web+proto`

Using the scan tool and the Burp extension together, to manipulate payloads and find hidden
endpoints, services and messages:

[![Watch the video](https://img.youtube.com/vi/VoDyweIjT2U/maxresdefault.jpg)](https://youtu.be/VoDyweIjT2U?si=kXWbQELnJZfyHaId)

> These predate the Java rewrite, so the Burp extension shown is the old Python one. The methodology
> still applies; only the extension's installation and UI have changed.

# gRPC Lab

To practise against something real, use the [gRPC & gRPC-Web lab](https://github.com/nxenon/grpc-lab).

# References

- [protoscope](https://github.com/protocolbuffers/protoscope) — used by `grpc_coder.py`
- [jsbeautifier](https://github.com/beautifier/js-beautify) and
  [texttable](https://github.com/foutaise/texttable) — used by `grpc_scan.py`
- [blackboxprotobuf](https://github.com/nccgroup/blackboxprotobuf) — vendored by the old Python Burp
  extension on the [`legacy/python-tools`](../../tree/legacy/python-tools) branch

# License

See [LICENSE](LICENSE).
