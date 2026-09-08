package com.nxenon.grpcweb.analyze;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class JsAnalyzerTest {

    /** A formatted extract in the shape protoc-gen-grpc-web emits. */
    private static final String FORMATTED_JS = """
            /**
             * @const
             * @type {!grpc.web.MethodDescriptor<
             *   !proto.auth.LoginRequest,
             *   !proto.auth.LoginResponse>}
             */
            const methodDescriptor_AuthService_Login = new grpc.web.MethodDescriptor(
              '/auth.AuthService/Login',
              grpc.web.MethodType.UNARY,
              proto.auth.LoginRequest,
              proto.auth.LoginResponse,
              function(request) {
                return request.serializeBinary();
              },
              proto.auth.LoginResponse.deserializeBinary
            );

            proto.auth.AuthServiceClient.prototype.login = function(request, metadata, callback) {
              return this.client_.rpcCall(this.hostname_ +
                  '/auth.AuthService/Login',
                  request,
                  metadata || {},
                  methodDescriptor_AuthService_Login,
                  callback);
            };

            /**
             * @param {string} value
             * @return {!proto.auth.LoginRequest} returns this
             */
            proto.auth.LoginRequest.prototype.setUserName = function(value) {
              return jspb.Message.setProto3StringField(this, 1, value);
            };

            proto.auth.LoginRequest.prototype.setPassword = function(value) {
              return jspb.Message.setProto3StringField(this, 2, value);
            };

            proto.auth.LoginRequest.prototype.setRememberMe = function(value) {
              return jspb.Message.setProto3BooleanField(this, 3, value);
            };

            proto.auth.LoginResponse.prototype.setToken = function(value) {
              return jspb.Message.setProto3StringField(this, 1, value);
            };

            proto.auth.LoginResponse.prototype.setExpiresAt = function(value) {
              return jspb.Message.setProto3IntField(this, 2, value);
            };
            """;

    /** The same content with newlines collapsed, as a bundler would ship it. */
    private static final String MINIFIED_JS =
            "var a=new grpc.web.MethodDescriptor('/auth.AuthService/Login',"
            + "grpc.web.MethodType.UNARY,proto.auth.LoginRequest,proto.auth.LoginResponse);"
            + "proto.auth.LoginRequest.prototype.setUserName=function(value){"
            + "return jspb.Message.setProto3StringField(this,1,value);};"
            + "proto.auth.LoginRequest.prototype.setPassword=function(value){"
            + "return jspb.Message.setProto3StringField(this,2,value);};";

    @Nested
    class Endpoints {

        @Test
        @DisplayName("a method path is found in formatted source")
        void findsEndpointInFormattedSource() {
            JsAnalyzer.Result result = JsAnalyzer.analyze(FORMATTED_JS);
            assertEquals(1, result.endpoints().size());

            GrpcEndpoint endpoint = result.endpoints().get(0);
            assertEquals("/auth.AuthService/Login", endpoint.path());
            assertEquals("auth.AuthService", endpoint.service());
            assertEquals("Login", endpoint.method());
        }

        @Test
        @DisplayName("the same path appearing twice is reported once")
        void duplicatesAreCollapsed() {
            // FORMATTED_JS contains the path in both MethodDescriptor and rpcCall.
            assertTrue(FORMATTED_JS.split("/auth\\.AuthService/Login", -1).length - 1 >= 2);
            assertEquals(1, JsAnalyzer.analyze(FORMATTED_JS).endpoints().size());
        }

        @Test
        @DisplayName("paths are found in minified source without a beautifier")
        void findsEndpointInMinifiedSource() {
            JsAnalyzer.Result result = JsAnalyzer.analyze(MINIFIED_JS);
            assertEquals(1, result.endpoints().size());
            assertEquals("/auth.AuthService/Login", result.endpoints().get(0).path());
        }

        @Test
        @DisplayName("an rpcCall path with no MethodDescriptor nearby is still found")
        void findsBarePathLiteral() {
            String js = "this.client_.rpcCall(h+\"/hidden.AdminService/DeleteUser\",r,{},d,cb);";
            JsAnalyzer.Result result = JsAnalyzer.analyze(js);
            assertEquals(1, result.endpoints().size());
            assertEquals("/hidden.AdminService/DeleteUser", result.endpoints().get(0).path());
        }

        @Test
        @DisplayName("multiple services are all reported, sorted by path")
        void multipleServicesAreSorted() {
            String js = "'/z.Svc/M' '/a.Svc/M' '/m.Svc/M'";
            List<GrpcEndpoint> endpoints = JsAnalyzer.analyze(js).endpoints();
            assertEquals(List.of("/a.Svc/M", "/m.Svc/M", "/z.Svc/M"),
                    endpoints.stream().map(GrpcEndpoint::path).toList());
        }

        @Test
        @DisplayName("ordinary URL paths are not mistaken for gRPC routes")
        void plainUrlPathsAreIgnored() {
            // Outside a gRPC call site a path needs a dotted service segment to count, otherwise
            // every two-segment URL in the bundle would be reported as an endpoint.
            String js = "fetch('/api/users'); fetch('/static/app.js'); fetch('/');"
                    + "axios.get('/v1/orders'); window.location='/login/callback';";
            assertTrue(JsAnalyzer.analyze(js).endpoints().isEmpty(),
                    JsAnalyzer.analyze(js).endpoints().toString());
        }

        @Test
        @DisplayName("a package-less path is found when it sits in a gRPC call site")
        void unqualifiedPathInCallSiteIsFound() {
            // A .proto with no package declaration produces /MyService/Method, which is
            // indistinguishable from a normal URL except by where it appears.
            String js = "var d=new grpc.web.MethodDescriptor('/MyService/DoThing',"
                    + "grpc.web.MethodType.UNARY,Req,Res);";
            List<GrpcEndpoint> endpoints = JsAnalyzer.analyze(js).endpoints();
            assertEquals(1, endpoints.size());
            assertEquals("/MyService/DoThing", endpoints.get(0).path());
            assertEquals("MyService", endpoints.get(0).service());
            assertEquals("DoThing", endpoints.get(0).method());
        }

        @Test
        @DisplayName("a package-less path found via rpcCall is reported")
        void unqualifiedPathInRpcCallIsFound() {
            String js = "this.client_.rpcCall(this.hostname_+'/Admin/Delete',r,{},d,cb);";
            assertEquals(List.of("/Admin/Delete"),
                    JsAnalyzer.analyze(js).endpoints().stream()
                            .map(GrpcEndpoint::path).toList());
        }

        @Test
        @DisplayName("a plain URL near a gRPC call site is still reported, and that is accepted")
        void callSiteWindowMayIncludeNeighbouringPaths() {
            // The window after a call site is deliberately generous: missing a real endpoint is
            // worse than listing one extra path for the tester to dismiss.
            String js = "rpcCall('/a.B/C', r); fetch('/api/users');";
            assertTrue(JsAnalyzer.analyze(js).endpoints().size() >= 1);
        }
    }

    @Nested
    class Messages {

        @Test
        @DisplayName("messages and their fields are recovered with field numbers")
        void recoversMessageFields() {
            JsAnalyzer.Result result = JsAnalyzer.analyze(FORMATTED_JS);

            assertEquals(2, result.messages().size());
            assertTrue(result.messages().containsKey("auth.LoginRequest"));
            assertTrue(result.messages().containsKey("auth.LoginResponse"));

            List<GrpcMessageField> request = result.messages().get("auth.LoginRequest");
            assertEquals(3, request.size());
            assertEquals("user_name", request.get(0).fieldName());
            assertEquals(1, request.get(0).fieldNumber());
            assertEquals("string", request.get(0).inferredType());
            assertEquals("remember_me", request.get(2).fieldName());
            assertEquals(3, request.get(2).fieldNumber());
            assertEquals("bool", request.get(2).inferredType());
        }

        @Test
        @DisplayName("fields are ordered by field number, not by source order")
        void fieldsAreOrderedByNumber() {
            String js = "proto.p.M.prototype.setB=function(v){"
                    + "return jspb.Message.setProto3StringField(this,9,v);};"
                    + "proto.p.M.prototype.setA=function(v){"
                    + "return jspb.Message.setProto3StringField(this,2,v);};";
            List<GrpcMessageField> fields = JsAnalyzer.analyze(js).messages().get("p.M");
            assertEquals(List.of(2, 9), fields.stream().map(GrpcMessageField::fieldNumber).toList());
        }

        @Test
        @DisplayName("setters are found in minified source")
        void findsSettersInMinifiedSource() {
            JsAnalyzer.Result result = JsAnalyzer.analyze(MINIFIED_JS);
            List<GrpcMessageField> fields = result.messages().get("auth.LoginRequest");
            assertEquals(2, fields.size());
            assertEquals("user_name", fields.get(0).fieldName());
            assertEquals("password", fields.get(1).fieldName());
        }

        @Test
        @DisplayName("a getter fills in a field whose setter was not emitted")
        void getterActsAsFallback() {
            String js = "proto.p.M.prototype.getTagsList=function(){"
                    + "return jspb.Message.getRepeatedField(this,4);};";
            List<GrpcMessageField> fields = JsAnalyzer.analyze(js).messages().get("p.M");
            assertEquals(1, fields.size());
            assertEquals(4, fields.get(0).fieldNumber());
        }

        @Test
        @DisplayName("a setter wins over a getter for the same field number")
        void setterTakesPrecedenceOverGetter() {
            String js = "proto.p.M.prototype.setName=function(v){"
                    + "return jspb.Message.setProto3StringField(this,1,v);};"
                    + "proto.p.M.prototype.getName=function(){"
                    + "return jspb.Message.getFieldWithDefault(this,1,'');};";
            List<GrpcMessageField> fields = JsAnalyzer.analyze(js).messages().get("p.M");
            assertEquals(1, fields.size());
            assertEquals("string", fields.get(0).inferredType());
        }

        @Test
        void fieldCountSumsAcrossMessages() {
            assertEquals(5, JsAnalyzer.analyze(FORMATTED_JS).fieldCount());
        }
    }

    @Nested
    class TypeInference {

        @ParameterizedTest
        @CsvSource({
                "Proto3StringField,string",
                "Proto3BytesField,bytes",
                "Proto3BooleanField,bool",
                "Proto3IntField,int",
                "Proto3FloatField,double",
                "WrapperField,message",
                "RepeatedField,repeated",
                "SomethingUnrecognised,?",
        })
        @DisplayName("jspb accessors map to Type Definition tab types")
        void jspbAccessorsMapToTypes(String accessor, String expected) {
            String js = "proto.p.M.prototype.setX=function(v){"
                    + "return jspb.Message.set" + accessor + "(this,1,v);};";
            assertEquals(expected, JsAnalyzer.analyze(js).messages().get("p.M").get(0).inferredType());
        }
    }

    @Nested
    class NameConversion {

        @ParameterizedTest
        @CsvSource({
                "UserName,user_name",
                "Password,password",
                "A,a",
                "ID,id",
                "HTTPPort,http_port",
                "UserID,user_id",
                "Field1,field1",
                "OAuthToken,o_auth_token",
        })
        @DisplayName("accessor suffixes convert back to proto field names")
        void accessorNamesConvert(String accessor, String expected) {
            assertEquals(expected, JsAnalyzer.toSnakeCase(accessor));
        }
    }

    @Nested
    class RepeatedFieldNames {

        @Test
        @DisplayName("protoc's List suffix is dropped from a repeated accessor")
        void listSuffixIsStripped() {
            // Runs on the raw accessor suffix, before toSnakeCase, so the result is still
            // CamelCase at this stage.
            assertEquals("Roles", JsAnalyzer.stripListSuffix("RolesList", "RepeatedField"));
            assertEquals("Tags", JsAnalyzer.stripListSuffix("TagsList", "getRepeatedField"));
        }

        @Test
        @DisplayName("a non-repeated accessor keeps a trailing List")
        void nonRepeatedAccessorKeepsItsName() {
            // A singular string field genuinely called "block_list" must not become "block".
            assertEquals("BlockList",
                    JsAnalyzer.stripListSuffix("BlockList", "Proto3StringField"));
        }

        @Test
        @DisplayName("an accessor that is only \"List\" is left alone")
        void bareListAccessorIsLeftAlone() {
            assertEquals("List", JsAnalyzer.stripListSuffix("List", "RepeatedField"));
        }

        @Test
        @DisplayName("a repeated getter yields the field name without the suffix end to end")
        void endToEnd() {
            String js = "proto.p.M.prototype.getTagsList=function(){"
                    + "return jspb.Message.getRepeatedField(this,7);};";
            GrpcMessageField field = JsAnalyzer.analyze(js).messages().get("p.M").get(0);
            assertEquals("tags", field.fieldName());
            assertEquals(7, field.fieldNumber());
        }
    }

    @Nested
    class ResultOrdering {

        @Test
        @DisplayName("messages come back sorted by name, not in an arbitrary hash order")
        void messagesAreSortedByName() {
            String js = "proto.z.Last.prototype.setA=function(v){"
                    + "return jspb.Message.setProto3StringField(this,1,v);};"
                    + "proto.a.First.prototype.setA=function(v){"
                    + "return jspb.Message.setProto3StringField(this,1,v);};"
                    + "proto.m.Middle.prototype.setA=function(v){"
                    + "return jspb.Message.setProto3StringField(this,1,v);};";
            assertEquals(List.of("a.First", "m.Middle", "z.Last"),
                    List.copyOf(JsAnalyzer.analyze(js).messages().keySet()));
        }
    }

    @Nested
    class HostileInput {

        @Test
        @DisplayName("null and empty input yield an empty result rather than throwing")
        void emptyInputIsSafe() {
            assertTrue(JsAnalyzer.analyze(null).isEmpty());
            assertTrue(JsAnalyzer.analyze("").isEmpty());
            assertTrue(JsAnalyzer.analyze("this is not javascript at all").isEmpty());
        }

        @Test
        @DisplayName("input past the size cap is refused instead of scanned")
        void oversizedInputIsRefused() {
            String oversized = "a".repeat(JsAnalyzer.MAX_INPUT_LENGTH + 1);
            assertTrue(JsAnalyzer.analyze(oversized).isEmpty());
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("a partial setter repeated many times does not cause runaway backtracking")
        void partialMatchesDoNotBacktrack() {
            // The old pattern used unbounded .* between every part, which backtracks badly on
            // near-misses like these: the prefix matches but the jspb call never arrives.
            String hostile = "proto.a.b.prototype.setX = function(value) { "
                    + "x".repeat(500) + " } ";
            JsAnalyzer.Result result = JsAnalyzer.analyze(hostile.repeat(2000));
            assertTrue(result.messages().isEmpty());
        }

        @Test
        @Timeout(value = 10, unit = TimeUnit.SECONDS)
        @DisplayName("long runs of quotes and slashes do not cause runaway backtracking")
        void quoteRunsDoNotBacktrack() {
            assertTrue(JsAnalyzer.analyze("'/".repeat(200_000)).endpoints().isEmpty());
            assertTrue(JsAnalyzer.analyze("/'".repeat(200_000)).endpoints().isEmpty());
        }

        @Test
        @DisplayName("a field number of zero is skipped, since protobuf has no field 0")
        void zeroFieldNumberIsSkipped() {
            String js = "proto.p.M.prototype.setX=function(v){"
                    + "return jspb.Message.setProto3StringField(this,0,v);};";
            assertFalse(JsAnalyzer.analyze(js).messages().containsKey("p.M"));
        }

        @Test
        @DisplayName("a field number too large for an int is skipped, not crashed on")
        void oversizedFieldNumberIsSkipped() {
            String js = "proto.p.M.prototype.setX=function(v){"
                    + "return jspb.Message.setProto3StringField(this,9999999999,v);};";
            assertTrue(JsAnalyzer.analyze(js).isEmpty());
        }
    }
}
