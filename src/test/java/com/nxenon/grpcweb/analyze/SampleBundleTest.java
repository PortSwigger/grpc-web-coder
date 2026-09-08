package com.nxenon.grpcweb.analyze;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the results of analyzing {@code samples/auth_grpc_web_pb.js}.
 *
 * <p>That file exists so the analyzer can be exercised in Burp without a live target, and the
 * README quotes what it should produce. Asserting it here keeps the sample, the documentation and
 * the analyzer from drifting apart.
 */
class SampleBundleTest {

    private static JsAnalyzer.Result result;

    @BeforeAll
    static void analyzeSample() throws IOException {
        Path sample = Path.of("samples", "auth_grpc_web_pb.js");
        assertTrue(Files.exists(sample), "sample bundle is missing: " + sample.toAbsolutePath());
        result = JsAnalyzer.analyze(Files.readString(sample));
    }

    @Test
    @DisplayName("all six routes are found, in sorted order")
    void endpointsAreFound() {
        assertEquals(List.of(
                "/LegacyService/Ping",
                "/admin.AdminService/DeleteUser",
                "/admin.AdminService/ImpersonateUser",
                "/auth.AuthService/Login",
                "/auth.AuthService/RefreshToken",
                "/auth.AuthService/StreamEvents"),
                result.endpoints().stream().map(GrpcEndpoint::path).toList());
    }

    @Test
    @DisplayName("the admin routes no UI calls are among them")
    void unlinkedRoutesAreFound() {
        List<String> paths = result.endpoints().stream().map(GrpcEndpoint::path).toList();
        assertTrue(paths.contains("/admin.AdminService/DeleteUser"));
        assertTrue(paths.contains("/admin.AdminService/ImpersonateUser"));
    }

    @Test
    @DisplayName("the package-less route is found via its call site")
    void packagelessRouteIsFound() {
        assertTrue(result.endpoints().stream()
                .anyMatch(endpoint -> endpoint.path().equals("/LegacyService/Ping")));
    }

    @Test
    @DisplayName("the sample's decoy application URLs are not reported as routes")
    void decoyUrlsAreIgnored() {
        List<String> paths = result.endpoints().stream().map(GrpcEndpoint::path).toList();
        for (String decoy : List.of("/static/logo.svg", "/api/health", "/login/callback")) {
            assertFalse(paths.contains(decoy), "reported a plain URL as a route: " + decoy);
        }
    }

    @Test
    @DisplayName("messages are listed in sorted order")
    void messagesAreSorted() {
        assertEquals(List.of(
                "admin.DeleteUserRequest",
                "admin.ImpersonateRequest",
                "auth.Event",
                "auth.LoginRequest",
                "auth.LoginResponse"),
                List.copyOf(result.messages().keySet()));
    }

    @Test
    @DisplayName("every field of the login request is recovered with its number and type")
    void loginRequestFields() {
        List<GrpcMessageField> fields = result.messages().get("auth.LoginRequest");
        assertEquals(5, fields.size());
        assertEquals("user_name",          fields.get(0).fieldName());
        assertEquals("string",             fields.get(0).inferredType());
        assertEquals("password",           fields.get(1).fieldName());
        assertEquals("remember_me",        fields.get(2).fieldName());
        assertEquals("bool",               fields.get(2).inferredType());
        assertEquals("totp_code",          fields.get(3).fieldName());
        assertEquals("int",                fields.get(3).inferredType());
        assertEquals("device_fingerprint", fields.get(4).fieldName());
        assertEquals("bytes",              fields.get(4).inferredType());
    }

    @Test
    @DisplayName("a repeated field is named without protoc's List suffix")
    void repeatedFieldNameHasNoListSuffix() {
        GrpcMessageField roles = result.messages().get("auth.LoginResponse").stream()
                .filter(field -> field.fieldNumber() == 4)
                .findFirst()
                .orElseThrow();
        assertEquals("roles", roles.fieldName(),
                "getRolesList describes a field called roles, not roles_list");
        assertEquals("repeated", roles.inferredType());
    }

    @Test
    @DisplayName("the minified tail is parsed without a beautifier")
    void minifiedSectionIsParsed() {
        List<GrpcMessageField> event = result.messages().get("auth.Event");
        assertEquals(3, event.size());
        assertEquals("kind", event.get(0).fieldName());
        assertEquals("payload", event.get(1).fieldName());
        assertEquals("timestamp_ms", event.get(2).fieldName());
    }

    @Test
    @DisplayName("the totals quoted in the README hold")
    void documentedTotals() {
        assertEquals(6, result.endpoints().size());
        assertEquals(5, result.messages().size());
        assertEquals(16, result.fieldCount());
    }
}
