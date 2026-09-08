package com.nxenon.grpcweb.export;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.nxenon.grpcweb.analyze.JsAnalyzer;
import com.nxenon.grpcweb.protobuf.JsonCodec;
import com.nxenon.grpcweb.protobuf.TypeDefinition;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers each export format over the sample bundle's findings.
 *
 * <p>An export is only worth having if the thing on the other end accepts it, so the checks here go
 * past "it produced text": the JSON is reparsed, the type definitions are fed back through the
 * extension's own type definition parser, and the {@code .proto} is compiled by {@code protoc} when
 * one is on the path.
 */
class AnalysisExporterTest {

    private static AnalysisResults results;

    @BeforeAll
    static void analyzeSample() throws IOException {
        Path sample = Path.of("samples", "auth_grpc_web_pb.js");
        assertTrue(Files.exists(sample), "sample bundle is missing");
        JsAnalyzer.Result analysis = JsAnalyzer.analyze(Files.readString(sample));

        results = new AnalysisResults();
        int added = results.add("http://127.0.0.1:8000/auth_grpc_web_pb.js", analysis);
        assertTrue(added > 0, "the sample should produce findings");
    }

    @ParameterizedTest
    @EnumSource(AnalysisExporter.Format.class)
    @DisplayName("every format produces non-empty output and has a sensible filename")
    void everyFormatProducesOutput(AnalysisExporter.Format format) {
        String exported = AnalysisExporter.export(results, format);
        assertNotNull(exported);
        assertFalse(exported.isBlank(), format + " produced nothing");

        assertTrue(format.defaultFileName().endsWith("." + format.extension()),
                format.defaultFileName() + " should end with ." + format.extension());
        assertFalse(format.label().isBlank());
        assertFalse(format.description().isBlank());
    }

    @ParameterizedTest
    @EnumSource(AnalysisExporter.Format.class)
    @DisplayName("every format handles empty results without throwing")
    void everyFormatHandlesEmptyResults(AnalysisExporter.Format format) {
        String exported = AnalysisExporter.export(new AnalysisResults(), format);
        assertNotNull(exported, format + " returned null for empty results");
    }

    @Nested
    class Json {

        @Test
        @DisplayName("the JSON reparses and carries every endpoint and message")
        void jsonIsWellFormed() {
            JsonObject root = JsonParser.parseString(
                    AnalysisExporter.export(results, AnalysisExporter.Format.JSON))
                    .getAsJsonObject();

            assertEquals(6, root.getAsJsonArray("endpoints").size());
            assertEquals(5, root.getAsJsonArray("messages").size());
        }

        @Test
        @DisplayName("an endpoint carries its call style and message types")
        void endpointDetailIsIncluded() {
            JsonObject root = JsonParser.parseString(
                    AnalysisExporter.export(results, AnalysisExporter.Format.JSON))
                    .getAsJsonObject();

            JsonObject streaming = null;
            for (var element : root.getAsJsonArray("endpoints")) {
                JsonObject endpoint = element.getAsJsonObject();
                if ("/auth.AuthService/StreamEvents".equals(endpoint.get("path").getAsString())) {
                    streaming = endpoint;
                }
            }
            assertNotNull(streaming, "the streaming endpoint should be present");
            assertEquals("server streaming", streaming.get("callStyle").getAsString());
            assertEquals("auth.EventRequest", streaming.get("requestType").getAsString());
            assertEquals("auth.Event", streaming.get("responseType").getAsString());
        }

        @Test
        @DisplayName("a message's fields carry name, number and type")
        void fieldDetailIsIncluded() {
            JsonObject root = JsonParser.parseString(
                    AnalysisExporter.export(results, AnalysisExporter.Format.JSON))
                    .getAsJsonObject();

            for (var element : root.getAsJsonArray("messages")) {
                JsonObject message = element.getAsJsonObject();
                if (!"auth.LoginRequest".equals(message.get("name").getAsString())) {
                    continue;
                }
                var fields = message.getAsJsonArray("fields");
                assertEquals(5, fields.size());
                JsonObject first = fields.get(0).getAsJsonObject();
                assertEquals("user_name", first.get("name").getAsString());
                assertEquals(1, first.get("number").getAsInt());
                assertEquals("string", first.get("type").getAsString());
                return;
            }
            throw new AssertionError("auth.LoginRequest missing from the export");
        }
    }

    @Nested
    class Csv {

        @Test
        @DisplayName("the endpoints CSV has a header and one row per endpoint")
        void endpointsCsvShape() {
            List<String> lines = AnalysisExporter
                    .export(results, AnalysisExporter.Format.ENDPOINTS_CSV)
                    .lines().toList();

            assertEquals("source,path,service,method,call_style,request_type,response_type",
                    lines.get(0));
            assertEquals(7, lines.size(), "header plus six endpoints");
            assertTrue(lines.stream().anyMatch(line -> line.contains("server streaming")));
        }

        @Test
        @DisplayName("the fields CSV has a header and one row per field")
        void fieldsCsvShape() {
            List<String> lines = AnalysisExporter
                    .export(results, AnalysisExporter.Format.FIELDS_CSV)
                    .lines().toList();

            assertEquals("source,message,field,number,type,jspb_accessor", lines.get(0));
            assertEquals(17, lines.size(), "header plus sixteen fields");
        }

        @Test
        @DisplayName("a value containing a comma or quote is quoted per RFC 4180")
        void specialCharactersAreQuoted() {
            AnalysisResults awkward = new AnalysisResults();
            awkward.add("http://host/a,b\"c.js", JsAnalyzer.analyze(
                    "proto.p.M.prototype.setX=function(v){"
                            + "return jspb.Message.setProto3StringField(this,1,v);};"));

            String csv = AnalysisExporter.export(awkward, AnalysisExporter.Format.FIELDS_CSV);
            assertTrue(csv.contains("\"http://host/a,b\"\"c.js\""), csv);
        }

        @Test
        @DisplayName("a value that a spreadsheet would treat as a formula is defused")
        void formulaInjectionIsDefused() {
            // Source labels come from a target's URL, so a crafted one must not execute when the
            // tester opens their own exported report.
            AnalysisResults hostile = new AnalysisResults();
            hostile.add("=cmd|'/c calc'!A1", JsAnalyzer.analyze(
                    "proto.p.M.prototype.setX=function(v){"
                            + "return jspb.Message.setProto3StringField(this,1,v);};"));

            String csv = AnalysisExporter.export(hostile, AnalysisExporter.Format.FIELDS_CSV);
            assertFalse(csv.contains("\n=cmd"), "a leading = must not survive unescaped");
            assertTrue(csv.contains("'=cmd"), csv);
        }
    }

    @Nested
    class Proto {

        private String proto() {
            return AnalysisExporter.export(results, AnalysisExporter.Format.PROTO);
        }

        @Test
        @DisplayName("messages are emitted with their real field names and numbers")
        void messagesAreEmitted() {
            String proto = proto();
            assertTrue(proto.contains("message LoginRequest {"), proto);
            assertTrue(proto.contains("string user_name = 1;"), proto);
            assertTrue(proto.contains("bool remember_me = 3;"), proto);
            assertTrue(proto.contains("bytes device_fingerprint = 5;"), proto);
        }

        @Test
        @DisplayName("an integer field becomes int64, since jspb does not narrow it")
        void integersBecomeInt64() {
            assertTrue(proto().contains("int64 totp_code = 4;"), proto());
        }

        @Test
        @DisplayName("services carry real rpc signatures")
        void servicesAreEmitted() {
            String proto = proto();
            assertTrue(proto.contains("service AuthService {"), proto);
            assertTrue(proto.contains("rpc Login (LoginRequest) returns (LoginResponse);"), proto);
        }

        @Test
        @DisplayName("a server-streaming method is marked stream on its response")
        void streamingIsMarked() {
            assertTrue(proto().contains("rpc StreamEvents (EventRequest) returns (stream Event);"),
                    proto());
        }

        @Test
        @DisplayName("a message a service references but never defines becomes an empty stub")
        void referencedMessagesGetStubs() {
            String proto = proto();
            assertTrue(proto.contains("message RefreshRequest {}"), proto);
            assertTrue(proto.contains("message DeleteUserResponse {}"), proto);
        }

        @Test
        @DisplayName("a field whose type is unknown is commented out, not guessed")
        void unknownTypesAreCommentedOut() {
            String proto = proto();
            // profile is a WrapperField and roles a RepeatedField; neither says what it holds.
            assertTrue(proto.contains("// profile = 3;"), proto);
            assertTrue(proto.contains("// roles = 4;"), proto);
        }

        @Test
        @DisplayName("findings spanning several packages are split into per-file sections")
        void multiplePackagesAreSplit() {
            String proto = proto();
            assertTrue(proto.contains("// ===== file: auth.proto ====="), proto);
            assertTrue(proto.contains("// ===== file: admin.proto ====="), proto);
            assertTrue(proto.contains("package auth;"), proto);
            assertTrue(proto.contains("package admin;"), proto);
        }

        @Test
        @DisplayName("a single-package result is one clean file with no split markers")
        void singlePackageNeedsNoMarkers() {
            AnalysisResults single = new AnalysisResults();
            single.add("x.js", JsAnalyzer.analyze(
                    "var d=new grpc.web.MethodDescriptor('/only.Svc/M',"
                            + "grpc.web.MethodType.UNARY,proto.only.Req,proto.only.Res);"
                            + "proto.only.Req.prototype.setA=function(v){"
                            + "return jspb.Message.setProto3StringField(this,1,v);};"));

            String proto = AnalysisExporter.export(single, AnalysisExporter.Format.PROTO);
            assertFalse(proto.contains("====="), proto);
            assertTrue(proto.contains("package only;"), proto);
            assertTrue(proto.startsWith("syntax = \"proto3\";"), proto);
        }

        @Test
        @DisplayName("the reconstructed schema compiles with protoc")
        void protocAcceptsTheOutput() throws Exception {
            Path protoc = findProtoc();
            org.junit.jupiter.api.Assumptions.assumeTrue(protoc != null,
                    "protoc is not installed; skipping the compile check");

            Path directory = Files.createTempDirectory("grpc-web-coder-proto");
            List<Path> files = writeProtoSections(proto(), directory);
            assertFalse(files.isEmpty(), "the export should yield at least one .proto file");

            List<String> command = new java.util.ArrayList<>();
            command.add(protoc.toString());
            command.add("--descriptor_set_out=" + directory.resolve("out.desc"));
            command.add("--proto_path=" + directory);
            files.forEach(file -> command.add(file.getFileName().toString()));

            Process process = new ProcessBuilder(command)
                    .directory(directory.toFile())
                    .redirectErrorStream(true)
                    .start();
            String output = new String(process.getInputStream().readAllBytes(),
                    java.nio.charset.StandardCharsets.UTF_8);
            int exitCode = process.waitFor();

            assertEquals(0, exitCode, "protoc rejected the reconstructed schema:\n" + output);
        }

        /** Splits the export at its file markers, or writes one file when there are none. */
        private List<Path> writeProtoSections(String proto, Path directory) throws IOException {
            List<Path> files = new java.util.ArrayList<>();
            java.util.regex.Matcher marker = java.util.regex.Pattern
                    .compile("// ===== file: (\\S+?) =====")
                    .matcher(proto);

            List<int[]> spans = new java.util.ArrayList<>();
            List<String> names = new java.util.ArrayList<>();
            while (marker.find()) {
                names.add(marker.group(1));
                spans.add(new int[]{marker.end(), proto.length()});
                if (spans.size() > 1) {
                    spans.get(spans.size() - 2)[1] = marker.start();
                }
            }
            if (names.isEmpty()) {
                Path file = directory.resolve("schema.proto");
                Files.writeString(file, proto);
                files.add(file);
                return files;
            }
            for (int i = 0; i < names.size(); i++) {
                Path file = directory.resolve(names.get(i));
                Files.writeString(file, proto.substring(spans.get(i)[0], spans.get(i)[1]));
                files.add(file);
            }
            return files;
        }

        private Path findProtoc() {
            for (String candidate : List.of(
                    "/opt/homebrew/bin/protoc", "/usr/local/bin/protoc", "/usr/bin/protoc")) {
                Path path = Path.of(candidate);
                if (Files.isExecutable(path)) {
                    return path;
                }
            }
            return null;
        }
    }

    @Nested
    class TypeDefinitions {

        @Test
        @DisplayName("there is one entry per discovered message")
        void oneEntryPerMessage() {
            JsonObject root = JsonParser.parseString(
                    AnalysisExporter.export(results, AnalysisExporter.Format.TYPE_DEFINITIONS))
                    .getAsJsonObject();
            assertEquals(5, root.size());
            assertTrue(root.has("auth.LoginRequest"));
        }

        @Test
        @DisplayName("a message's entry parses as a type definition the decoder accepts")
        void entriesAreValidTypeDefinitions() throws Exception {
            JsonObject root = JsonParser.parseString(
                    AnalysisExporter.export(results, AnalysisExporter.Format.TYPE_DEFINITIONS))
                    .getAsJsonObject();

            // This is the round-trip that matters: whatever the analyzer exports must be
            // pasteable straight into the Type Definition tab.
            for (String message : root.keySet()) {
                String json = root.get(message).toString();
                TypeDefinition definition = JsonCodec.jsonToTypeDefinition(json);
                assertNotNull(definition, message);
            }
        }

        @Test
        @DisplayName("field names and numbers come through, so payloads decode with real names")
        void namesAndNumbersAreCarried() throws Exception {
            JsonObject root = JsonParser.parseString(
                    AnalysisExporter.export(results, AnalysisExporter.Format.TYPE_DEFINITIONS))
                    .getAsJsonObject();

            TypeDefinition definition = JsonCodec.jsonToTypeDefinition(
                    root.get("auth.LoginRequest").toString());

            assertEquals("user_name", definition.get(1).name());
            assertEquals("string", definition.get(1).type().label());
            assertEquals("device_fingerprint", definition.get(5).name());
            assertEquals("bytes", definition.get(5).type().label());
        }

        @Test
        @DisplayName("a field whose type is unknown is omitted rather than guessed")
        void unmappableFieldsAreOmitted() throws Exception {
            JsonObject root = JsonParser.parseString(
                    AnalysisExporter.export(results, AnalysisExporter.Format.TYPE_DEFINITIONS))
                    .getAsJsonObject();

            JsonObject loginResponse = root.getAsJsonObject("auth.LoginResponse");
            assertTrue(loginResponse.has("1"), "access_token should be present");
            assertTrue(loginResponse.has("2"), "expires_at should be present");
            assertTrue(loginResponse.has("3"), "profile is a wrapper field, so it maps to message");
            assertFalse(loginResponse.has("4"),
                    "roles is repeated with an unknown element type, so it must be left out");
        }

        @Test
        @DisplayName("a message field carries an empty nested definition for the decoder to fill")
        void messageFieldsGetAnEmptyNestedDefinition() {
            JsonObject root = JsonParser.parseString(
                    AnalysisExporter.export(results, AnalysisExporter.Format.TYPE_DEFINITIONS))
                    .getAsJsonObject();

            JsonObject profile = root.getAsJsonObject("auth.LoginResponse").getAsJsonObject("3");
            assertEquals("message", profile.get("type").getAsString());
            assertTrue(profile.has("message_typedef"));
            assertEquals(0, profile.getAsJsonObject("message_typedef").size());
        }
    }

    @Nested
    class Accumulation {

        @Test
        @DisplayName("analyzing the same file twice does not duplicate rows")
        void duplicatesAreNotAdded() throws IOException {
            JsAnalyzer.Result analysis = JsAnalyzer.analyze(
                    Files.readString(Path.of("samples", "auth_grpc_web_pb.js")));

            AnalysisResults accumulated = new AnalysisResults();
            int first = accumulated.add("same.js", analysis);
            int second = accumulated.add("same.js", analysis);

            assertTrue(first > 0);
            assertEquals(0, second, "a repeated analysis of one file should add nothing");
            assertEquals(6, accumulated.endpointCount());
            assertEquals(16, accumulated.fieldCount());
        }

        @Test
        @DisplayName("the same finding from two different files is kept twice, attributed to each")
        void differentSourcesAreKeptSeparately() throws IOException {
            JsAnalyzer.Result analysis = JsAnalyzer.analyze(
                    Files.readString(Path.of("samples", "auth_grpc_web_pb.js")));

            AnalysisResults accumulated = new AnalysisResults();
            accumulated.add("bundle-a.js", analysis);
            accumulated.add("bundle-b.js", analysis);

            assertEquals(12, accumulated.endpointCount());
            // Distinct endpoints collapse for the .proto export, which must not emit duplicates.
            assertEquals(6, accumulated.distinctEndpoints().size());
        }

        @Test
        @DisplayName("clearing empties the model")
        void clearEmptiesEverything() throws IOException {
            AnalysisResults accumulated = new AnalysisResults();
            accumulated.add("x.js", JsAnalyzer.analyze(
                    Files.readString(Path.of("samples", "auth_grpc_web_pb.js"))));
            assertFalse(accumulated.isEmpty());

            accumulated.clear();
            assertTrue(accumulated.isEmpty());
            assertEquals(0, accumulated.endpointCount());
            assertEquals(0, accumulated.fieldCount());
        }
    }
}
