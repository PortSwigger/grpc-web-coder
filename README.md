# gRPC-Web Coder

Burp Suite extension that encodes and decodes gRPC-Web payloads without the target's `.proto`
schema, and maps a target's gRPC services from its JavaScript. Built on the Montoya API.

- **BApp Store:** [gRPC-Web Coder](https://portswigger.net/bappstore/63b92be302fa4521bf18d74b3adbbc00)

Supported content types:

- `application/grpc-web-text`, `application/grpc-web-text+proto`
- `application/grpc-web+proto`, `application/grpc-web`

> This repository previously held the Python gRPC-Pentest-Suite tools. They are still available —
> see [Older Python tools](#older-python-tools) at the end.

## Contents

- [Build](#build)
- [Install in Burp](#install-in-burp)
- [Test it locally](#test-it-locally)
- [How the tabs work](#how-the-tabs-work)
- [Analyzing JavaScript](#analyzing-javascript)
- [Design notes](#design-notes)
- [Tests](#tests)
- [Older Python tools](#older-python-tools)
- [Articles, video and lab](#articles-video-and-lab)

## Build

Requires a JDK 17 or later. Nothing else — the Gradle wrapper fetches Gradle itself.

```bash
./gradlew clean fatJar
```

If that fails with `Unable to locate a Java Runtime`, a JDK is installed but not on your `PATH`.
Homebrew's OpenJDK is keg-only and is not linked by default, so point `JAVA_HOME` at it:

```bash
export JAVA_HOME=$(brew --prefix openjdk@21)
```

The extension jar lands at `build/libs/grpc-web-coder-all.jar`, with protobuf-java and gson bundled
inside it.

## Install in Burp

1. **Extensions** → **Installed** → **Add**
2. Extension type: **Java**
3. Select `build/libs/grpc-web-coder-all.jar`
4. Click **Next**

The **Output** tab should show `gRPC-Web Coder loaded.` and a **gRPC-Web Coder** tab appears in
Burp's top-level tab bar.

## Test it locally

You do not need a gRPC server. Burp decodes the request as soon as it is in Repeater, so pasting a
request is enough to exercise the whole decode/edit/encode path.

### 1. Paste a request into Repeater

**Repeater** → right-click → **Paste request** (or create a new tab and paste over the contents):

```http
POST /auth.AuthService/Login HTTP/1.1
Host: grpc-web.example.com
Content-Type: application/grpc-web-text
X-Grpc-Web: 1
Accept: application/grpc-web-text
Content-Length: 36

AAAAABQKBWFkbWluEgtzdXBlcnNlY3JldA==
```

Open the **Decoded gRPC-Web** tab. The **Payload** tab shows:

```json
{
  "1": "admin",
  "2": "supersecret"
}
```

and **Type Definition** shows:

```json
{
  "1": {
    "type": "string",
    "alt_types": [ "bytes" ]
  },
  "2": {
    "type": "string",
    "alt_types": [ "bytes" ]
  }
}
```

Change `admin` to `administrator` in the Payload tab, then switch to the **Raw** tab. The base64 body
and `Content-Length` will both have been rewritten.

### 2. A message with nesting, numbers and a sub-message

```http
POST /search.SearchService/Query HTTP/1.1
Host: grpc-web.example.com
Content-Type: application/grpc-web-text
X-Grpc-Web: 1
Content-Length: 56

AAAAACQIKhILc2VhcmNoIHRlcm0aEQoMMTkyLjE2OC4xLjEwEJA/IAE=
```

Decodes to:

```json
{
  "1": 42,
  "2": "search term",
  "3": {
    "1": "192.168.1.10",
    "2": 8080
  },
  "4": 1
}
```

Field `4` is a `1` that the wire format cannot distinguish from `true`. Click **Edit type
definition**, change field `4` to `"type": "bool"`, and click **Apply** — the Payload tab redecodes
and now reads `"4": true`.

### 3. A streaming response with a trailer frame

To see the response side, put this in Repeater's **Response** panel by using
**Extensions** → your own test target, or simply confirm the decode against a saved item. A response
body of:

```
AAAAAA4KCnJlc3VsdCBvbmUQAQAAAAAOCgpyZXN1bHQgdHdvEAKAAAAAHmdycGMtc3RhdHVzOjANCmdycGMtbWVzc2FnZToNCg==
```

served with `Content-Type: application/grpc-web-text` decodes to:

```json
{
  "frames": [
    { "message": { "1": "result one", "2": 1 } },
    { "message": { "1": "result two", "2": 2 } },
    { "trailers": "grpc-status:0\r\ngrpc-message:\r\n" }
  ]
}
```

Two data frames and the trailer frame, all of them. A decoder that reads only the first frame — or
that folds the frame's flag byte into its length — shows you just `result one` and stops.

### 4. Test the JavaScript analyzer with no live target

`samples/auth_grpc_web_pb.js` is a bundle shaped like real protoc-gen-grpc-web output. Serve it over
HTTP and fetch it through Burp:

```bash
cd samples && python3 -m http.server 8000
```

In Burp **Repeater**, paste this request and send it:

```http
GET /auth_grpc_web_pb.js HTTP/1.1
Host: 127.0.0.1:8000
Accept: */*
Connection: close


```

Then right-click anywhere in that Repeater tab and choose **Analyze gRPC-Web Endpoints**. The
**gRPC-Web Coder** suite tab fills in with 6 endpoints and 16 fields across 5 messages:

| Path |
| --- |
| `/LegacyService/Ping` |
| `/admin.AdminService/DeleteUser` |
| `/admin.AdminService/ImpersonateUser` |
| `/auth.AuthService/Login` |
| `/auth.AuthService/RefreshToken` |
| `/auth.AuthService/StreamEvents` |

Three things in that list are worth noticing:

- `/admin.AdminService/DeleteUser` and `/admin.AdminService/ImpersonateUser` are never called by any
  UI code in the sample. Unlinked routes like these are the point of the feature.
- `/LegacyService/Ping` has no package segment, because it came from a `.proto` with no `package`
  declaration. It is found because it sits inside an `rpcCall`, not because of its shape.
- The sample also contains `/static/logo.svg`, `/api/health` and `/login/callback`, and none of them
  are listed. A two-segment path only counts as a route inside a gRPC call site.

In the **Message fields** table, `auth.LoginRequest` comes out as:

| Field | Number | Type |
| --- | --- | --- |
| `user_name` | 1 | `string` |
| `password` | 2 | `string` |
| `remember_me` | 3 | `bool` |
| `totp_code` | 4 | `int` |
| `device_fingerprint` | 5 | `bytes` |

Which is exactly what the Type Definition tab wants — copy those names and numbers in and a decoded
payload stops reading as `{"1": ..., "2": ...}`.

To exercise the gzip path too, serve the file compressed:

```bash
cd samples && gzip -kf auth_grpc_web_pb.js && python3 -c "
import http.server
class H(http.server.SimpleHTTPRequestHandler):
    def do_GET(self):
        body = open('auth_grpc_web_pb.js.gz','rb').read()
        self.send_response(200)
        self.send_header('Content-Type','application/javascript')
        self.send_header('Content-Encoding','gzip')
        self.send_header('Content-Length',str(len(body)))
        self.end_headers(); self.wfile.write(body)
http.server.HTTPServer(('127.0.0.1',8000),H).serve_forever()"
```

Analyze that response and the results are identical — Burp's own compression utilities inflate it
first.

### 5. Binary `application/grpc-web+proto`

The binary format cannot be pasted as text. To test it, either:

- flip **Body format detection** to **Always treat bodies as application/grpc-web+proto** and send a
  request whose body you set from a file, or
- use a real gRPC-Web target. The [grpc-pentest-suite](https://github.com/nxenon/grpc-pentest-suite)
  repository has a vulnerable gRPC-Web application for exactly this.

### 6. Confirm nothing is corrupted when you change nothing

Worth doing once, because it is the property that matters most: open a gRPC-Web request in Repeater,
open the **Decoded gRPC-Web** tab, switch back to **Raw** without editing anything, and check the body
is byte-for-byte what it was. The test suite asserts this over the reference message and 1200
randomly generated ones, but seeing it in Burp is reassuring.

## How the tabs work

**Payload** holds the decoded messages as JSON and is editable.

- A body with one data frame and no trailer is a bare message object.
- Anything else — several frames, a trailer, a compressed frame — is a `{ "frames": [ ... ] }` list.
- Field keys are field numbers, or names if you give them in the type definition.
- `bytes` fields are hex strings, so every byte survives an edit.

**Type Definition** holds the type of each field. It starts read-only, showing what was detected.
Click **Edit type definition** to make it editable, **Apply** to redecode the body with your version,
and **Reset to detected** to go back to the guess.

```json
{
  "1": { "type": "string", "name": "username" },
  "2": { "type": "int" },
  "3": {
    "type": "message",
    "message_typedef": { "1": { "type": "string" } }
  }
}
```

The shorthand `"1": "string"` also works. Naming a field changes its key in the Payload tab, which
is how the JavaScript analyzer's output becomes useful.

`alt_types` is informational: it lists the other readings the same bytes would allow. The wire format
does not record whether a varint was an `int32`, a `uint64`, a `sint32` or a `bool`, nor whether a
length-delimited field was a nested message, a string, raw bytes or a packed array — so where the
guess could go either way, the alternatives are listed rather than hidden.

Available types:

| Category | Types |
| --- | --- |
| Varint | `int`, `uint`, `sint`, `bool` |
| 32-bit | `fixed32`, `sfixed32`, `float` |
| 64-bit | `fixed64`, `sfixed64`, `double` |
| Length-delimited | `string`, `bytes`, `message` |
| Packed | `packed_int`, `packed_uint`, `packed_sint`, `packed_bool`, `packed_fixed32`, `packed_sfixed32`, `packed_float`, `packed_fixed64`, `packed_sfixed64`, `packed_double` |
| Legacy | `group` |

Proto-style aliases (`int32`, `uint64`, `sint32`, …) are accepted too.

## Analyzing JavaScript

Right-click a response in **Proxy** → **HTTP history**, the site map, or Repeater, and choose
**Analyze gRPC-Web Endpoints**. Results appear in the **gRPC-Web Coder** suite tab as two sortable
tables:

- **Endpoints** — every `/package.Service/Method` route found, including ones the UI never calls,
  with the call style and message types where the JavaScript revealed them.
- **Message fields** — each message with its field names, numbers and types, which is exactly what
  the Type Definition tab wants.

Target the site's `*_grpc_web_pb.js` bundle, or whatever bundle it was compiled into. Minified files
are handled directly; there is no beautify step. Analysis runs on a background thread, so Burp stays
responsive on large bundles.

Where a `MethodDescriptor` is present, the call style and the request and response message types are
recovered too, which is what makes the `.proto` export below possible.

### Exporting the results

Findings accumulate across runs, so analyze every bundle a target ships and export once. Pick a
format, then **Export to file...** or **Copy to clipboard**:

| Format | What it is for |
| --- | --- |
| **JSON (everything)** | Every endpoint and field, with the response each came from. For a report or further tooling. |
| **Endpoints (CSV)** | One row per endpoint. For a spreadsheet or a report table. |
| **Message fields (CSV)** | One row per field. Same. |
| **Protobuf schema (.proto)** | A reconstructed `.proto`, for `protoc` or `grpcurl`. |
| **Type definitions (JSON)** | Per-message type definitions to paste into the Type Definition tab. |

**Type definitions** is the one that closes the loop. Export it, copy the object for the message you
are looking at, paste it into the **Type Definition** tab and click **Apply** — the payload stops
reading as `{"1": ..., "2": ...}` and starts reading as:

```json
{
  "user_name": "admin",
  "password": "supersecret",
  "remember_me": true
}
```

Fields whose type the JavaScript does not reveal are left out rather than guessed, because the
decoder infers anything missing from a definition; an omission costs a name but never produces a
wrong type.

**Protobuf schema** reconstructs a compiling `.proto` from the same findings. From the sample bundle:

```proto
syntax = "proto3";

package auth;

message LoginRequest {
  string user_name = 1;
  string password = 2;
  bool remember_me = 3;
  int64 totp_code = 4;
  bytes device_fingerprint = 5;
}

service AuthService {
  rpc Login (LoginRequest) returns (LoginResponse);
  rpc RefreshToken (RefreshRequest) returns (LoginResponse);
  rpc StreamEvents (EventRequest) returns (stream Event);
}
```

Points worth knowing about that output:

- Field names and numbers are exact. Types are the closest match to the jspb accessor, which cannot
  tell `int32` from `int64`, `uint32` or an enum — so integers become `int64`.
- A field whose type the JavaScript does not reveal is emitted as a comment rather than guessed, so
  the file still compiles and the name and number are still on record.
- A message a service references but whose fields were never seen becomes an empty stub, again so
  the file compiles.
- A `.proto` declares one package, so when findings span several packages each is emitted as a
  complete section behind a `// ===== file: auth.proto =====` marker. Split at the markers. A
  single-package result is just one clean file with no markers.

The test suite compiles this output with `protoc` on every run where `protoc` is installed, so
"it compiles" is checked rather than hoped for.

## Design notes

### No schema, so types are inferred

The protobuf wire format carries a field number and a three-bit wire type, and nothing else. Field
names and exact types live only in the `.proto`. So:

- A varint is reported as `int`, which re-encodes any varint byte-for-byte.
- A length-delimited field is tried as a nested message first, then as text, then as raw bytes. The
  nested-message attempt is strict: the bytes must consume exactly, field numbers must be in range,
  and groups must balance. A lenient attempt would read half of every short string as a message.
- Valid UTF-8 that contains a NUL or a control character is reported as `bytes`, not as a string.
  `"bytes\0aaa"` decodes cleanly as UTF-8 but is plainly binary, and hex keeps it editable.
- Packed repeated fields are never guessed, because on the wire they are exactly a `bytes` field.
  They are offered in `alt_types` when the payload would parse as one.

### Bugs fixed from the Jython version

- **Frame length.** The old code read all five header bytes as one hex integer, folding the flag byte
  into the length. It worked only while that byte was zero, and broke on the first compressed or
  trailer frame.
- **Only the first frame.** Streaming responses were truncated, and trailer frames were dropped.
- **Byte corruption.** `decode("unicode_escape")` followed by `encode("utf-8")` mangled every byte
  above 0x7F, so binary and non-ASCII fields did not survive an edit. Bodies are `byte[]` here and
  `bytes` fields are hex.
- **Stale `Content-Length`.** The body was rebuilt but the original headers were reused verbatim.
  `withBody` updates the length.
- **Nested type definitions were discarded.** Only top-level field types were shown, so re-encoding
  a message with nesting used a definition that had lost its inner types.
- **`_resetButton` did not exist.** It was referenced in the action listener but never created, so
  clicking any other button raised `AttributeError`.
- **Contradictory settings.** Five checkboxes enabled and disabled each other by hand and could be
  driven into states that disagreed. Detection is now one radio group.

### Dependencies

- [montoya-api](https://github.com/PortSwigger/burp-extensions-montoya-api) — compile-time only,
  provided by Burp.
- [protobuf-java](https://github.com/protocolbuffers/protobuf) — `CodedInputStream` and
  `CodedOutputStream` for wire-level reads and writes, hardened against malformed input. The type
  inference on top is this project's own.
- [gson](https://github.com/google/gson) — JSON for the editor tabs.

There is no Java port of [blackboxprotobuf](https://github.com/nccgroup/blackboxprotobuf), so the
schema-less inference layer is implemented here directly, in
`src/main/java/com/nxenon/grpcweb/protobuf/`.

## Tests

```bash
./gradlew test
```

351 tests covering the wire codec, framing, base64 chunking, JSON mapping, the JavaScript
analyzer, settings persistence, the editor tabs and extension loading. The properties they pin
down:

- **Round-trip fidelity.** The reference `TestMessage` covering every protobuf type, fourteen
  hand-written payload shapes, and 1200 randomly generated messages all re-encode to identical
  bytes.
- **No crashes on hostile input.** 5000 random byte strings are decoded; each must either decode and
  re-encode, or be refused with a `ProtobufException`. Nothing else may escape.
- **Framing.** Truncated headers, truncated payloads, and a frame claiming 4 GiB are all refused,
  and a trailer frame's flag byte is not read as part of its length.
- **Regex safety.** The JavaScript patterns are checked against inputs designed to make a
  backtracking regex hang.
- **Nothing is rewritten by accident.** Driving the editor tabs through Burp's own interfaces, a
  message that was not edited comes back byte-identical, and a payload edited into invalid JSON
  leaves the original request object untouched.
- **The extension loads.** `initialize` is run against a stand-in Burp, which checks the editors,
  suite tab, context menu and unloading handler are all registered and that unloading is clean.
- **The README is correct.** The worked examples above are asserted against real decoder output,
  and `samples/auth_grpc_web_pb.js` is asserted to produce the endpoint and field tables shown
  above, so neither can drift from what the extension does.
- **Exports are consumable.** The JSON is reparsed, the exported type definitions are fed back
  through the extension's own type-definition parser, and the reconstructed `.proto` is compiled by
  `protoc` where it is installed. CSV values are checked for RFC 4180 quoting and for spreadsheet
  formula injection, since a source label comes from a target's URL.

An HTML report is written to `build/reports/tests/test/index.html`.

## Older Python tools

Everything this repository held before the Java rewrite is frozen on the
**[`legacy/python-tools`](../../tree/legacy/python-tools)** branch:

```bash
git checkout legacy/python-tools
```

That branch has the Jython Burp extensions (`grpc_web_burp_extension.py` and
`old_grpc_web_burp_extension_with_dependency.py`) and the standalone CLI tools
(`grpc_scan.py`, `grpc_coder.py`, `grpc_protobuf_decoder.py`, `big_string_chunker.py`). It is
unmaintained — the Java extension above replaces the Burp side of it.

Two things the CLI tools still do that the extension does not: decode protobuf that is not wrapped
in a gRPC-Web frame (`grpc_protobuf_decoder.py`, useful for a Kafka message), and chunk a long
string into 80-character protobuf pieces (`big_string_chunker.py`).

## License

GPL-3.0. See [LICENSE](LICENSE).

Bundled dependencies and their licences are listed in
[THIRD-PARTY-NOTICES.md](THIRD-PARTY-NOTICES.md).

## Upgrading from 1.x

Version 1.x was a Jython extension and required Jython to be installed and configured in Burp.
Version 2.0.0 is a Java rewrite: load `build/libs/grpc-web-coder-all.jar` as a **Java** extension
and remove the old Python entry. Nothing carries over — there is no saved state to migrate — and the
settings are re-created with sensible defaults on first load.

The rewrite is behaviour-compatible where 1.x was correct, and deliberately different where it was
not; see [Bugs fixed from the Jython version](#bugs-fixed-from-the-jython-version).

## Articles, video and lab

Methodology for pentesting gRPC-Web, including finding hidden services and endpoints:

- [Hacking into gRPC-Web](https://infosecwriteups.com/hacking-into-grpc-web-a54053757a45)
- [Hacking into gRPC-Web: Part 2](https://medium.com/@nxenon/hacking-into-grpc-web-part-2-f8540309e1e8)
  — covers `application/grpc-web+proto`
- [Video walkthrough](https://youtu.be/VoDyweIjT2U?si=kXWbQELnJZfyHaId) — predates the Java rewrite,
  so the extension shown is the old Python one; the methodology still applies

To practise against something real, use the [gRPC & gRPC-Web lab](https://github.com/nxenon/grpc-lab).
