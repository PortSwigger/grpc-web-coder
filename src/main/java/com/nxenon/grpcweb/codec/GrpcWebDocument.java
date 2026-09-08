package com.nxenon.grpcweb.codec;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonParser;
import com.nxenon.grpcweb.protobuf.JsonCodec;
import com.nxenon.grpcweb.protobuf.MessageValue;
import com.nxenon.grpcweb.protobuf.ProtobufDecoder;
import com.nxenon.grpcweb.protobuf.ProtobufEncoder;
import com.nxenon.grpcweb.protobuf.ProtobufException;
import com.nxenon.grpcweb.protobuf.TypeDefinition;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * A whole gRPC-Web body, decoded: every frame's protobuf message as editable JSON, plus the one
 * type definition shared by all of them.
 *
 * <h2>Why one type definition</h2>
 * Every data frame in a gRPC-Web stream carries the same protobuf message type — a server-streaming
 * response repeats one response message. The frames are therefore decoded through a single
 * accumulating {@link TypeDefinition}, so a field that only appears in the third frame still ends up
 * in the definition.
 *
 * <h2>Document shapes</h2>
 * A body with exactly one data frame and no trailer renders as the message object on its own, which
 * is the common case for a request:
 * <pre>{@code { "1": "value" } }</pre>
 * Anything else — multiple frames, a trailer, or a compressed frame — renders as a frame list:
 * <pre>{@code
 * {
 *   "frames": [
 *     { "message": { "1": "value" } },
 *     { "trailers": "grpc-status:0\r\n" }
 *   ]
 * }
 * }</pre>
 */
public final class GrpcWebDocument {

    private static final Gson PRETTY = new GsonBuilder()
            .setPrettyPrinting()
            .disableHtmlEscaping()
            .create();

    private static final String KEY_FRAMES = "frames";
    private static final String KEY_MESSAGE = "message";
    private static final String KEY_TRAILERS = "trailers";
    private static final String KEY_COMPRESSED = "compressed";
    private static final String KEY_RAW = "raw";

    private final List<Frame> frames;
    private final TypeDefinition typeDefinition;
    private final GrpcWebFormat format;

    /** One decoded frame: either a protobuf message, trailer text, or bytes that would not decode. */
    private static final class Frame {
        private final boolean trailer;
        private final boolean compressed;
        private final MessageValue message;
        private final String trailerText;
        private final byte[] raw;

        private Frame(boolean trailer, boolean compressed, MessageValue message, String trailerText,
                      byte[] raw) {
            this.trailer = trailer;
            this.compressed = compressed;
            this.message = message;
            this.trailerText = trailerText;
            this.raw = raw;
        }

        static Frame message(MessageValue message, boolean compressed) {
            return new Frame(false, compressed, message, null, null);
        }

        static Frame trailers(String text, boolean compressed) {
            return new Frame(true, compressed, null, text, null);
        }

        static Frame raw(byte[] bytes, boolean compressed, boolean trailer) {
            return new Frame(trailer, compressed, null, null, bytes);
        }
    }

    private GrpcWebDocument(List<Frame> frames, TypeDefinition typeDefinition, GrpcWebFormat format) {
        this.frames = frames;
        this.typeDefinition = typeDefinition;
        this.format = format;
    }

    // ---------------------------------------------------------------- decode

    /**
     * Decodes a body, inferring the type definition.
     *
     * @throws GrpcWebException if the frame stream is malformed
     * @throws ProtobufException if a data frame is not a valid protobuf message
     */
    public static GrpcWebDocument decode(byte[] body, GrpcWebFormat format)
            throws GrpcWebException, ProtobufException {
        return decode(body, format, new TypeDefinition());
    }

    /**
     * Decodes a body against an existing type definition, extending it with any field it does not
     * yet describe. Passing a user-edited definition here is what makes their type choices stick.
     */
    public static GrpcWebDocument decode(byte[] body, GrpcWebFormat format,
                                         TypeDefinition typeDefinition)
            throws GrpcWebException, ProtobufException {

        List<GrpcFrame> wireFrames = GrpcWebCodec.readBody(body, format);
        List<Frame> decoded = new ArrayList<>(wireFrames.size());

        for (GrpcFrame wireFrame : wireFrames) {
            byte[] payload = GrpcWebCodec.decompress(wireFrame);
            if (wireFrame.isTrailer()) {
                String text = ProtobufDecoder.tryDecodeUtf8(payload);
                if (text != null) {
                    decoded.add(Frame.trailers(text, wireFrame.isCompressed()));
                } else {
                    decoded.add(Frame.raw(payload, wireFrame.isCompressed(), true));
                }
                continue;
            }
            MessageValue message = ProtobufDecoder.decodeWith(payload, typeDefinition);
            decoded.add(Frame.message(message, wireFrame.isCompressed()));
        }
        return new GrpcWebDocument(decoded, typeDefinition, format);
    }

    // ------------------------------------------------------------------ view

    /** The number of frames in the body, including the trailer frame. */
    public int frameCount() {
        return frames.size();
    }

    public TypeDefinition typeDefinition() {
        return typeDefinition;
    }

    public GrpcWebFormat format() {
        return format;
    }

    /** True when the body renders as a bare message object rather than a frame list. */
    public boolean isSimple() {
        return frames.size() == 1
                && !frames.get(0).trailer
                && !frames.get(0).compressed
                && frames.get(0).message != null;
    }

    /** The decoded body as the JSON shown in the Payload tab. */
    public String toPayloadJson() {
        if (isSimple()) {
            return JsonCodec.valueToJson(frames.get(0).message, typeDefinition);
        }
        JsonArray array = new JsonArray();
        for (Frame frame : frames) {
            JsonObject object = new JsonObject();
            if (frame.compressed) {
                object.addProperty(KEY_COMPRESSED, true);
            }
            if (frame.raw != null) {
                object.addProperty(KEY_RAW, com.nxenon.grpcweb.protobuf.Hex.encode(frame.raw));
            } else if (frame.trailer) {
                object.addProperty(KEY_TRAILERS, frame.trailerText);
            } else {
                object.add(KEY_MESSAGE,
                        JsonParser.parseString(JsonCodec.valueToJson(frame.message, typeDefinition)));
            }
            array.add(object);
        }
        JsonObject root = new JsonObject();
        root.add(KEY_FRAMES, array);
        return PRETTY.toJson(root);
    }

    /** The inferred or supplied type definition, as the JSON shown in the Type Definition tab. */
    public String toTypeDefinitionJson() {
        return JsonCodec.typeDefinitionToJson(typeDefinition);
    }

    // ---------------------------------------------------------------- encode

    /**
     * Encodes an edited payload document back into a gRPC-Web body.
     *
     * @param payloadJson        the edited Payload tab contents
     * @param typeDefinitionJson the edited Type Definition tab contents
     * @param format             the body encoding to produce
     * @throws ProtobufException if the JSON or the type definition is invalid
     * @throws GrpcWebException  if a frame cannot be recompressed
     */
    public static byte[] encode(String payloadJson, String typeDefinitionJson, GrpcWebFormat format)
            throws ProtobufException, GrpcWebException {

        TypeDefinition typeDefinition = JsonCodec.jsonToTypeDefinition(typeDefinitionJson);
        JsonElement root = parse(payloadJson);
        if (!root.isJsonObject()) {
            throw new ProtobufException("The payload must be a JSON object");
        }
        JsonObject object = root.getAsJsonObject();

        List<GrpcFrame> wireFrames = new ArrayList<>();
        if (isFrameList(object)) {
            for (JsonElement element : object.getAsJsonArray(KEY_FRAMES)) {
                if (!element.isJsonObject()) {
                    throw new ProtobufException("Each entry in \"frames\" must be a JSON object");
                }
                wireFrames.add(encodeFrame(element.getAsJsonObject(), typeDefinition));
            }
        } else {
            MessageValue value = JsonCodec.jsonToValue(payloadJson, typeDefinition);
            wireFrames.add(GrpcFrame.data(ProtobufEncoder.encode(value, typeDefinition)));
        }
        return GrpcWebCodec.writeBody(wireFrames, format);
    }

    /**
     * A document is a frame list only when {@code frames} is its single member and holds an array.
     * Requiring it to be the only member means a message whose own field is named {@code frames} is
     * not mistaken for one.
     */
    private static boolean isFrameList(JsonObject object) {
        return object.size() == 1
                && object.has(KEY_FRAMES)
                && object.get(KEY_FRAMES).isJsonArray();
    }

    private static GrpcFrame encodeFrame(JsonObject frame, TypeDefinition typeDefinition)
            throws ProtobufException, GrpcWebException {

        boolean compressed = frame.has(KEY_COMPRESSED)
                && frame.get(KEY_COMPRESSED).getAsBoolean();

        byte[] payload;
        int flags = 0;
        if (frame.has(KEY_RAW)) {
            payload = com.nxenon.grpcweb.protobuf.Hex.decode(frame.get(KEY_RAW).getAsString());
            if (frame.has(KEY_TRAILERS)) {
                flags |= GrpcFrame.FLAG_TRAILER;
            }
        } else if (frame.has(KEY_TRAILERS)) {
            payload = frame.get(KEY_TRAILERS).getAsString().getBytes(StandardCharsets.UTF_8);
            flags |= GrpcFrame.FLAG_TRAILER;
        } else if (frame.has(KEY_MESSAGE)) {
            JsonElement message = frame.get(KEY_MESSAGE);
            if (!message.isJsonObject()) {
                throw new ProtobufException("A frame's \"message\" must be a JSON object");
            }
            MessageValue value = JsonCodec.jsonToValue(message.toString(), typeDefinition);
            payload = ProtobufEncoder.encode(value, typeDefinition);
        } else {
            throw new ProtobufException(
                    "Each frame needs a \"message\", \"trailers\" or \"raw\" member");
        }

        if (compressed) {
            payload = GrpcWebCodec.compress(payload);
            flags |= GrpcFrame.FLAG_COMPRESSED;
        }
        return new GrpcFrame(flags, payload);
    }

    private static JsonElement parse(String json) throws ProtobufException {
        if (json == null || json.isBlank()) {
            throw new ProtobufException("No JSON to parse");
        }
        try {
            return JsonParser.parseString(json);
        } catch (JsonParseException e) {
            throw new ProtobufException("Invalid JSON: " + e.getMessage(), e);
        }
    }
}
