package com.nxenon.grpcweb.codec;

import com.nxenon.grpcweb.protobuf.FieldType;
import com.nxenon.grpcweb.protobuf.Hex;
import com.nxenon.grpcweb.protobuf.ProtobufException;
import com.nxenon.grpcweb.protobuf.TypeDefinition;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end coverage of the decode/edit/re-encode path a user actually drives from the message
 * editor, over bodies shaped like real gRPC-Web traffic.
 */
class GrpcWebDocumentTest {

    /** Unary request: one data frame holding {@code { 1: "amin", 2: 1337 }}. */
    private static final String UNARY_TEXT_BODY = "AAAAAAkKBGFtaW4QuQo=";

    /** Server-streaming response: two data frames plus a trailer frame. */
    private static final String STREAMING_TEXT_BODY =
            "AAAAAAUKA29uZQAAAAAFCgN0d2+AAAAAHmdycGMtc3RhdHVzOjANCmdycGMtbWVzc2FnZToNCg==";

    private static byte[] ascii(String text) {
        return text.getBytes(StandardCharsets.US_ASCII);
    }

    @Nested
    class UnaryRequests {

        @Test
        @DisplayName("a single-frame body renders as a bare message object")
        void singleFrameRendersAsObject() throws Exception {
            GrpcWebDocument document =
                    GrpcWebDocument.decode(ascii(UNARY_TEXT_BODY), GrpcWebFormat.TEXT);

            assertTrue(document.isSimple());
            assertEquals(1, document.frameCount());

            String payload = document.toPayloadJson();
            assertTrue(payload.contains("\"amin\""), payload);
            assertTrue(payload.contains("1337"), payload);
            assertFalse(payload.contains("frames"), "a unary body should not need the frame list");
        }

        @Test
        @DisplayName("the inferred type definition names both fields")
        void typeDefinitionIsInferred() throws Exception {
            GrpcWebDocument document =
                    GrpcWebDocument.decode(ascii(UNARY_TEXT_BODY), GrpcWebFormat.TEXT);

            assertEquals(FieldType.STRING, document.typeDefinition().get(1).type());
            assertEquals(FieldType.INT, document.typeDefinition().get(2).type());
            assertTrue(document.toTypeDefinitionJson().contains("string"));
        }

        @Test
        @DisplayName("an untouched body re-encodes to the original bytes")
        void untouchedBodyRoundTrips() throws Exception {
            byte[] body = ascii(UNARY_TEXT_BODY);
            GrpcWebDocument document = GrpcWebDocument.decode(body, GrpcWebFormat.TEXT);

            byte[] reencoded = GrpcWebDocument.encode(
                    document.toPayloadJson(), document.toTypeDefinitionJson(), GrpcWebFormat.TEXT);

            assertArrayEquals(body, reencoded, new String(reencoded, StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("editing a string field changes only that field")
        void editingAStringField() throws Exception {
            GrpcWebDocument document =
                    GrpcWebDocument.decode(ascii(UNARY_TEXT_BODY), GrpcWebFormat.TEXT);

            String edited = document.toPayloadJson().replace("amin", "administrator");
            byte[] reencoded = GrpcWebDocument.encode(
                    edited, document.toTypeDefinitionJson(), GrpcWebFormat.TEXT);

            // Decode the result and confirm the new value and the untouched neighbour.
            GrpcWebDocument roundTripped = GrpcWebDocument.decode(reencoded, GrpcWebFormat.TEXT);
            assertTrue(roundTripped.toPayloadJson().contains("administrator"));
            assertTrue(roundTripped.toPayloadJson().contains("1337"));
        }

        @Test
        @DisplayName("the same body works in the binary format")
        void protoFormatIsEquivalent() throws Exception {
            byte[] frames = Hex.decode("00000000090a04616d696e10b90a");
            GrpcWebDocument document = GrpcWebDocument.decode(frames, GrpcWebFormat.PROTO);

            assertTrue(document.isSimple());
            assertTrue(document.toPayloadJson().contains("amin"));
            assertArrayEquals(frames, GrpcWebDocument.encode(
                    document.toPayloadJson(), document.toTypeDefinitionJson(),
                    GrpcWebFormat.PROTO));
        }
    }

    @Nested
    class StreamingResponses {

        @Test
        @DisplayName("every data frame is decoded, and the trailer is shown as text")
        void allFramesAreDecoded() throws Exception {
            GrpcWebDocument document =
                    GrpcWebDocument.decode(ascii(STREAMING_TEXT_BODY), GrpcWebFormat.TEXT);

            assertFalse(document.isSimple());
            assertEquals(3, document.frameCount());

            String payload = document.toPayloadJson();
            assertTrue(payload.contains("\"frames\""), payload);
            assertTrue(payload.contains("\"one\""), payload);
            assertTrue(payload.contains("\"two\""), payload);
            assertTrue(payload.contains("grpc-status:0"), payload);
        }

        @Test
        @DisplayName("the second frame is not dropped, which is what truncated the old decoder")
        void secondFrameIsNotLost() throws Exception {
            GrpcWebDocument document =
                    GrpcWebDocument.decode(ascii(STREAMING_TEXT_BODY), GrpcWebFormat.TEXT);
            String payload = document.toPayloadJson();

            int first = payload.indexOf("\"one\"");
            int second = payload.indexOf("\"two\"");
            assertTrue(first >= 0 && second > first,
                    "both messages must appear, in order: " + payload);
        }

        @Test
        @DisplayName("a streaming body re-encodes to the original bytes")
        void streamingBodyRoundTrips() throws Exception {
            byte[] body = ascii(STREAMING_TEXT_BODY);
            GrpcWebDocument document = GrpcWebDocument.decode(body, GrpcWebFormat.TEXT);

            byte[] reencoded = GrpcWebDocument.encode(
                    document.toPayloadJson(), document.toTypeDefinitionJson(), GrpcWebFormat.TEXT);

            assertArrayEquals(body, reencoded, new String(reencoded, StandardCharsets.US_ASCII));
        }

        @Test
        @DisplayName("one type definition is shared by every data frame")
        void oneTypeDefinitionCoversAllFrames() throws Exception {
            GrpcWebDocument document =
                    GrpcWebDocument.decode(ascii(STREAMING_TEXT_BODY), GrpcWebFormat.TEXT);
            // Both frames carry field 1 as a string, so the definition has exactly one entry.
            assertEquals(1, document.typeDefinition().size());
            assertEquals(FieldType.STRING, document.typeDefinition().get(1).type());
        }

        @Test
        @DisplayName("a field that appears only in a later frame still reaches the definition")
        void laterFrameFieldsAccumulate() throws Exception {
            // Frame one has field 1 only; frame two adds field 2.
            byte[] frames = Hex.decode(
                    "00000000050a036f6e65"      // frame 1: { 1: "one" }
                    + "0000000002107b");        // frame 2: { 2: 123 }
            GrpcWebDocument document = GrpcWebDocument.decode(frames, GrpcWebFormat.PROTO);

            assertEquals(2, document.typeDefinition().size());
            assertEquals(FieldType.STRING, document.typeDefinition().get(1).type());
            assertEquals(FieldType.INT, document.typeDefinition().get(2).type());
        }

        @Test
        @DisplayName("editing a trailer's status code works")
        void trailerIsEditable() throws Exception {
            GrpcWebDocument document =
                    GrpcWebDocument.decode(ascii(STREAMING_TEXT_BODY), GrpcWebFormat.TEXT);

            String edited = document.toPayloadJson().replace("grpc-status:0", "grpc-status:7");
            byte[] reencoded = GrpcWebDocument.encode(
                    edited, document.toTypeDefinitionJson(), GrpcWebFormat.TEXT);

            GrpcWebDocument roundTripped = GrpcWebDocument.decode(reencoded, GrpcWebFormat.TEXT);
            assertTrue(roundTripped.toPayloadJson().contains("grpc-status:7"));
        }
    }

    @Nested
    class CompressedFrames {

        @Test
        @DisplayName("a gzip data frame decodes, and re-encodes still compressed")
        void compressedFrameRoundTrips() throws Exception {
            byte[] message = Hex.decode("0a04616d696e10b90a");
            byte[] frames = GrpcWebCodec.writeFrames(
                    java.util.List.of(GrpcFrame.compressedData(GrpcWebCodec.compress(message))));

            GrpcWebDocument document = GrpcWebDocument.decode(frames, GrpcWebFormat.PROTO);
            assertFalse(document.isSimple(), "a compressed frame needs the frame list form");

            String payload = document.toPayloadJson();
            assertTrue(payload.contains("\"compressed\": true"), payload);
            assertTrue(payload.contains("amin"), payload);

            byte[] reencoded = GrpcWebDocument.encode(
                    payload, document.toTypeDefinitionJson(), GrpcWebFormat.PROTO);
            GrpcWebDocument roundTripped =
                    GrpcWebDocument.decode(reencoded, GrpcWebFormat.PROTO);
            assertTrue(roundTripped.toPayloadJson().contains("amin"));
            assertTrue(GrpcWebCodec.readFrames(reencoded).get(0).isCompressed());
        }
    }

    @Nested
    class UserEdits {

        @Test
        @DisplayName("a type definition override changes how a field is read")
        void typeOverrideIsHonoured() throws Exception {
            // field 1 varint 1: guessed as int, but the user knows it is a bool.
            byte[] frames = Hex.decode("00000000020801");

            GrpcWebDocument inferred = GrpcWebDocument.decode(frames, GrpcWebFormat.PROTO);
            assertTrue(inferred.toPayloadJson().contains("1"));

            TypeDefinition asBool = new TypeDefinition();
            asBool.define(1, FieldType.BOOL);
            GrpcWebDocument overridden =
                    GrpcWebDocument.decode(frames, GrpcWebFormat.PROTO, asBool);

            assertTrue(overridden.toPayloadJson().contains("true"),
                    overridden.toPayloadJson());
            assertArrayEquals(frames, GrpcWebDocument.encode(
                    overridden.toPayloadJson(), overridden.toTypeDefinitionJson(),
                    GrpcWebFormat.PROTO));
        }

        @Test
        @DisplayName("adding a field to both tabs adds it to the encoded message")
        void addingAFieldWorks() throws Exception {
            byte[] encoded = GrpcWebDocument.encode(
                    "{\"1\": \"amin\", \"2\": 1337, \"3\": \"added\"}",
                    "{\"1\": \"string\", \"2\": \"int\", \"3\": \"string\"}",
                    GrpcWebFormat.PROTO);

            GrpcWebDocument document = GrpcWebDocument.decode(encoded, GrpcWebFormat.PROTO);
            assertTrue(document.toPayloadJson().contains("added"));
        }

        @Test
        @DisplayName("a payload field with no type definition entry is reported, not guessed")
        void unknownFieldIsReported() {
            ProtobufException e = assertThrows(ProtobufException.class,
                    () -> GrpcWebDocument.encode(
                            "{\"1\": \"amin\", \"9\": \"surprise\"}",
                            "{\"1\": \"string\"}",
                            GrpcWebFormat.PROTO));
            assertTrue(e.getMessage().contains("not in the type definition"), e.getMessage());
        }

        @Test
        @DisplayName("invalid JSON in the payload tab is reported clearly")
        void invalidJsonIsReported() {
            ProtobufException e = assertThrows(ProtobufException.class,
                    () -> GrpcWebDocument.encode("{ not json",
                            "{\"1\": \"string\"}", GrpcWebFormat.PROTO));
            assertTrue(e.getMessage().contains("Invalid JSON"), e.getMessage());
        }

        @Test
        @DisplayName("an unknown type name in the type definition tab is reported clearly")
        void unknownTypeNameIsReported() {
            ProtobufException e = assertThrows(ProtobufException.class,
                    () -> GrpcWebDocument.encode("{\"1\": \"x\"}",
                            "{\"1\": \"decimal\"}", GrpcWebFormat.PROTO));
            assertTrue(e.getMessage().contains("Unknown field type"), e.getMessage());
        }

        @Test
        @DisplayName("a message whose own field is named \"frames\" is not read as a frame list")
        void fieldNamedFramesIsNotAFrameList() throws Exception {
            // A single-member object called "frames" holding an array is the frame-list marker, so
            // a named field must not collide with it. Here "frames" holds a string, not an array.
            byte[] encoded = GrpcWebDocument.encode(
                    "{\"frames\": \"a value\"}",
                    "{\"1\": {\"type\": \"string\", \"name\": \"frames\"}}",
                    GrpcWebFormat.PROTO);

            GrpcWebDocument document = GrpcWebDocument.decode(encoded, GrpcWebFormat.PROTO);
            assertTrue(document.isSimple());
            assertTrue(document.toPayloadJson().contains("a value"));
        }
    }

    @Nested
    class MalformedBodies {

        @Test
        @DisplayName("a body that is not base64 is reported as such")
        void nonBase64TextBodyIsReported() {
            assertThrows(GrpcWebException.class,
                    () -> GrpcWebDocument.decode(ascii("!!!not base64!!!"), GrpcWebFormat.TEXT));
        }

        @Test
        @DisplayName("a truncated frame is reported rather than partly decoded")
        void truncatedFrameIsReported() {
            assertThrows(GrpcWebException.class, () -> GrpcWebDocument.decode(
                    Hex.decode("00000000ff0801"), GrpcWebFormat.PROTO));
        }

        @Test
        @DisplayName("a frame whose payload is not protobuf is reported")
        void nonProtobufFrameIsReported() {
            assertThrows(ProtobufException.class, () -> GrpcWebDocument.decode(
                    // frame length 3, payload ff ff ff, which is not a valid message
                    Hex.decode("0000000003ffffff"), GrpcWebFormat.PROTO));
        }

        @Test
        @DisplayName("an empty body decodes to zero frames without throwing")
        void emptyBodyIsHandled() throws Exception {
            GrpcWebDocument document = GrpcWebDocument.decode(new byte[0], GrpcWebFormat.PROTO);
            assertEquals(0, document.frameCount());
            assertFalse(document.isSimple());
        }
    }
}
