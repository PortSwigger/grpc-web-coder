package com.nxenon.grpcweb.codec;

import com.nxenon.grpcweb.protobuf.FieldType;
import com.nxenon.grpcweb.protobuf.TypeDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pins the worked examples in README.md to what the code actually produces.
 *
 * <p>Documentation that shows the wrong output is worse than none: a user following it concludes the
 * extension is broken. These are the exact bodies and Content-Length values printed in the
 * "Test it locally" section.
 */
class ReadmeExamplesTest {

    private static byte[] body(String base64Body) {
        return base64Body.getBytes(StandardCharsets.US_ASCII);
    }

    @Test
    @DisplayName("example 1: the login request decodes as documented")
    void loginExample() throws Exception {
        String base64Body = "AAAAABQKBWFkbWluEgtzdXBlcnNlY3JldA==";
        assertEquals(36, base64Body.length(), "the documented Content-Length must match");

        GrpcWebDocument document = GrpcWebDocument.decode(body(base64Body), GrpcWebFormat.TEXT);

        assertTrue(document.isSimple());
        assertEquals(1, document.frameCount());
        assertEquals("""
                {
                  "1": "admin",
                  "2": "supersecret"
                }""", document.toPayloadJson());

        assertEquals("""
                {
                  "1": {
                    "type": "string",
                    "alt_types": [
                      "bytes"
                    ]
                  },
                  "2": {
                    "type": "string",
                    "alt_types": [
                      "bytes"
                    ]
                  }
                }""", document.toTypeDefinitionJson());
    }

    @Test
    @DisplayName("example 1: changing admin to administrator re-encodes cleanly")
    void loginExampleEdit() throws Exception {
        String base64Body = "AAAAABQKBWFkbWluEgtzdXBlcnNlY3JldA==";
        GrpcWebDocument document = GrpcWebDocument.decode(body(base64Body), GrpcWebFormat.TEXT);

        byte[] edited = GrpcWebDocument.encode(
                document.toPayloadJson().replace("admin", "administrator"),
                document.toTypeDefinitionJson(),
                GrpcWebFormat.TEXT);

        GrpcWebDocument roundTripped = GrpcWebDocument.decode(edited, GrpcWebFormat.TEXT);
        assertEquals("""
                {
                  "1": "administrator",
                  "2": "supersecret"
                }""", roundTripped.toPayloadJson());
    }

    @Test
    @DisplayName("example 2: the nested search request decodes as documented")
    void nestedExample() throws Exception {
        String base64Body = "AAAAACQIKhILc2VhcmNoIHRlcm0aEQoMMTkyLjE2OC4xLjEwEJA/IAE=";
        assertEquals(56, base64Body.length(), "the documented Content-Length must match");

        GrpcWebDocument document = GrpcWebDocument.decode(body(base64Body), GrpcWebFormat.TEXT);

        assertEquals("""
                {
                  "1": 42,
                  "2": "search term",
                  "3": {
                    "1": "192.168.1.10",
                    "2": 8080
                  },
                  "4": 1
                }""", document.toPayloadJson());
    }

    @Test
    @DisplayName("example 2: field 4 offers bool, and selecting it reads 1 as true")
    void nestedExampleBoolOverride() throws Exception {
        String base64Body = "AAAAACQIKhILc2VhcmNoIHRlcm0aEQoMMTkyLjE2OC4xLjEwEJA/IAE=";
        GrpcWebDocument detected =
                GrpcWebDocument.decode(body(base64Body), GrpcWebFormat.TEXT);

        // The README tells the user to switch field 4 to bool, so bool must be on offer.
        assertTrue(detected.typeDefinition().get(4).alternativeTypes().contains(FieldType.BOOL));

        TypeDefinition edited = detected.typeDefinition();
        edited.get(4).setType(FieldType.BOOL);
        GrpcWebDocument overridden =
                GrpcWebDocument.decode(body(base64Body), GrpcWebFormat.TEXT, edited);

        assertTrue(overridden.toPayloadJson().contains("\"4\": true"),
                overridden.toPayloadJson());
        // And it still re-encodes to the original bytes.
        assertArrayEquals(body(base64Body), GrpcWebDocument.encode(
                overridden.toPayloadJson(), overridden.toTypeDefinitionJson(),
                GrpcWebFormat.TEXT));
    }

    @Test
    @DisplayName("example 3: the streaming response decodes to three frames as documented")
    void streamingExample() throws Exception {
        String base64Body = "AAAAAA4KCnJlc3VsdCBvbmUQAQAAAAAOCgpyZXN1bHQgdHdvEAKAAAAAHmdycGMt"
                + "c3RhdHVzOjANCmdycGMtbWVzc2FnZToNCg==";
        assertEquals(100, base64Body.length());

        GrpcWebDocument document = GrpcWebDocument.decode(body(base64Body), GrpcWebFormat.TEXT);

        assertEquals(3, document.frameCount());
        assertEquals("""
                {
                  "frames": [
                    {
                      "message": {
                        "1": "result one",
                        "2": 1
                      }
                    },
                    {
                      "message": {
                        "1": "result two",
                        "2": 2
                      }
                    },
                    {
                      "trailers": "grpc-status:0\\r\\ngrpc-message:\\r\\n"
                    }
                  ]
                }""", document.toPayloadJson());
    }

    @Test
    @DisplayName("example 5: an untouched body is returned byte-for-byte")
    void untouchedBodiesAreUnchanged() throws Exception {
        for (String base64Body : new String[]{
                "AAAAABQKBWFkbWluEgtzdXBlcnNlY3JldA==",
                "AAAAACQIKhILc2VhcmNoIHRlcm0aEQoMMTkyLjE2OC4xLjEwEJA/IAE=",
                "AAAAAA4KCnJlc3VsdCBvbmUQAQAAAAAOCgpyZXN1bHQgdHdvEAKAAAAAHmdycGMt"
                        + "c3RhdHVzOjANCmdycGMtbWVzc2FnZToNCg==",
        }) {
            GrpcWebDocument document = GrpcWebDocument.decode(body(base64Body), GrpcWebFormat.TEXT);
            assertArrayEquals(body(base64Body), GrpcWebDocument.encode(
                    document.toPayloadJson(), document.toTypeDefinitionJson(),
                    GrpcWebFormat.TEXT), base64Body);
        }
    }
}
