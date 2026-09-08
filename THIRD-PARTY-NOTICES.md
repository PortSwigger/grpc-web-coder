# Third-party notices

gRPC-Web Coder is licensed under the GNU General Public License v3.0 — see [LICENSE](LICENSE).

The BApp Store requires an extension to bundle its dependencies so that installation is one click
and cannot collide with another extension's versions. `grpc-web-coder-all.jar` therefore contains
the following third-party libraries. Both licences are compatible with GPL-3.0.

## protobuf-java

- Version: 4.29.3
- Copyright: Copyright 2008 Google Inc. All rights reserved.
- Licence: BSD 3-Clause
- Home: https://github.com/protocolbuffers/protobuf

Used for wire-level protobuf reads and writes (`CodedInputStream`, `CodedOutputStream`). The
schema-less type inference layered on top is part of this project, not of protobuf-java.

```
Redistribution and use in source and binary forms, with or without
modification, are permitted provided that the following conditions are
met:

    * Redistributions of source code must retain the above copyright
notice, this list of conditions and the following disclaimer.
    * Redistributions in binary form must reproduce the above
copyright notice, this list of conditions and the following disclaimer
in the documentation and/or other materials provided with the
distribution.
    * Neither the name of Google Inc. nor the names of its
contributors may be used to endorse or promote products derived from
this software without specific prior written permission.

THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS
"AS IS" AND ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT
LIMITED TO, THE IMPLIED WARRANTIES OF MERCHANTABILITY AND FITNESS FOR
A PARTICULAR PURPOSE ARE DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT
OWNER OR CONTRIBUTORS BE LIABLE FOR ANY DIRECT, INDIRECT, INCIDENTAL,
SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES (INCLUDING, BUT NOT
LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES; LOSS OF USE,
DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND ON ANY
THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
(INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE
OF THIS SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
```

## Gson

- Version: 2.11.0
- Copyright: Copyright 2008 Google Inc.
- Licence: Apache License 2.0
- Home: https://github.com/google/gson

Used for the JSON shown in the Payload and Type Definition tabs, and for the JSON export formats.
The full Apache 2.0 licence text is available at https://www.apache.org/licenses/LICENSE-2.0.

## Not bundled

- **Montoya API** (`net.portswigger.burp.extensions:montoya-api`) is a compile-only dependency,
  provided by Burp Suite at runtime, and is not present in the jar.
- **JUnit 5**, **Mockito** and **protoc** are used only to build and test the project.

## Previously bundled

Version 1.x vendored [blackboxprotobuf](https://github.com/nccgroup/blackboxprotobuf) and
[jsbeautifier](https://github.com/beautifier/js-beautify) as Python sources. Version 2.0.0 is a Java
rewrite and no longer includes either: the schema-less protobuf layer is implemented directly
against protobuf-java, and the JavaScript analyzer no longer needs a pretty-printer.
