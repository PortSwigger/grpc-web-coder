package com.nxenon.grpcweb.analyze;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Extracts gRPC service routes and protobuf message shapes from generated gRPC-Web JavaScript.
 *
 * <p>A gRPC-Web front end ships a {@code *_grpc_web_pb.js} bundle that names every method the
 * server exposes and every field of every message, including routes the UI never calls. Recovering
 * them turns a black-box gRPC-Web target into one with a known attack surface.
 *
 * <h2>Why there is no beautifier</h2>
 * Every pattern here tolerates arbitrary whitespace, so minified and formatted bundles are handled
 * the same way and no JavaScript pretty-printer is needed.
 *
 * <h2>Untrusted input</h2>
 * JavaScript fetched from a target is untrusted, and a regex with nested unbounded quantifiers can
 * be made to backtrack for a very long time on hostile input. The patterns below use negated
 * character classes with bounded repetition instead of {@code .*}, so matching stays linear, and
 * input above {@link #MAX_INPUT_LENGTH} is refused outright.
 */
public final class JsAnalyzer {

    /**
     * Largest script this will scan. Generated bundles are big but not unbounded, and a cap keeps a
     * hostile multi-hundred-megabyte response from pinning a core inside Burp.
     */
    static final int MAX_INPUT_LENGTH = 32 * 1024 * 1024;

    /** Longest identifier or path segment any pattern will match, to bound repetition. */
    private static final int MAX_IDENT = 200;

    /**
     * A fully-qualified gRPC method path in a string literal: {@code /package.Service/Method}.
     *
     * <p>The dotted service segment is required, which is what separates a gRPC route from an
     * ordinary URL such as {@code /api/users}. Matching the literal anywhere, rather than only
     * inside {@code MethodDescriptor(...)}, also catches the
     * {@code rpcCall(hostname + '/pkg.Svc/Method', ...)} shape.
     */
    private static final Pattern QUALIFIED_ENDPOINT = Pattern.compile(
            "['\"](/([A-Za-z_$][A-Za-z0-9_$]{0,"
                    + MAX_IDENT + "}(?:\\.[A-Za-z_$][A-Za-z0-9_$]{0," + MAX_IDENT + "})+)"
                    + "/([A-Za-z_$][A-Za-z0-9_$]{0," + MAX_IDENT + "}))['\"]");

    /**
     * A method path with no package qualifier, e.g. {@code /MyService/Method}.
     *
     * <p>A {@code .proto} without a {@code package} declaration produces exactly this, so ignoring
     * it would miss real endpoints. On its own it is indistinguishable from any two-segment URL,
     * so it counts only inside a gRPC call site — see {@link #GRPC_CALL_SITE}.
     */
    private static final Pattern UNQUALIFIED_ENDPOINT = Pattern.compile(
            "['\"](/([A-Za-z_$][A-Za-z0-9_$.]{0," + MAX_IDENT + "})"
                    + "/([A-Za-z_$][A-Za-z0-9_$]{0," + MAX_IDENT + "}))['\"]");

    /**
     * A generated {@code MethodDescriptor}, which carries the path, the call style and the request
     * and response message types:
     * <pre>{@code
     * new grpc.web.MethodDescriptor(
     *   '/auth.AuthService/Login',
     *   grpc.web.MethodType.UNARY,
     *   proto.auth.LoginRequest,
     *   proto.auth.LoginResponse,
     * }</pre>
     * Whitespace is optional throughout, so the minified form matches the same pattern.
     */
    private static final Pattern METHOD_DESCRIPTOR = Pattern.compile(
            "MethodDescriptor\\s*\\(\\s*"
                    + "['\"](/([A-Za-z_$][A-Za-z0-9_$.]{0," + MAX_IDENT + "})"
                    + "/([A-Za-z_$][A-Za-z0-9_$]{0," + MAX_IDENT + "}))['\"]\\s*,\\s*"
                    + "(?:[A-Za-z0-9_$.]{0,80}MethodType\\.([A-Z_]{1,40})\\s*,\\s*)?"
                    + "proto\\.([A-Za-z0-9_$.]{1," + MAX_IDENT + "})\\s*,\\s*"
                    + "proto\\.([A-Za-z0-9_$.]{1," + MAX_IDENT + "})");

    /** The generated-client calls whose arguments are gRPC method paths. */
    private static final Pattern GRPC_CALL_SITE = Pattern.compile(
            "\\b(?:MethodDescriptor|AbstractClientBase\\.MethodInfo|methodInfo"
                    + "|rpcCall|unaryCall|serverStreaming|clientStreaming|invoke)\\s*\\(");

    /**
     * How far past a gRPC call site to look for its path argument. The path is the first or second
     * argument in every generated shape, so a few hundred characters is ample, and a bounded window
     * keeps the relaxed pattern from being applied to the whole script.
     */
    private static final int CALL_SITE_WINDOW = 400;

    /**
     * A generated setter, which carries the message name, the field name and the field number:
     * <pre>{@code
     * proto.pkg.Msg.prototype.setUserName = function(value) {
     *   return jspb.Message.setProto3StringField(this, 3, value);
     * };
     * }</pre>
     */
    private static final Pattern SETTER = Pattern.compile(
            "proto\\.([A-Za-z_$][A-Za-z0-9_$.]{0," + MAX_IDENT + "})"
                    + "\\.prototype\\.set([A-Za-z0-9_$]{1," + MAX_IDENT + "})"
                    + "\\s*=\\s*function\\s*\\([^)]{0,200}\\)\\s*\\{"
                    + "[^{}]{0,400}?"
                    + "jspb\\.Message\\.set([A-Za-z0-9_$]{1,80})"
                    + "\\s*\\(\\s*this\\s*,\\s*(\\d{1,10})");

    /**
     * A generated getter, used as a fallback for fields whose setter was not emitted, which happens
     * for {@code repeated} and map fields in some protoc-gen-grpc-web versions.
     */
    private static final Pattern GETTER = Pattern.compile(
            "proto\\.([A-Za-z_$][A-Za-z0-9_$.]{0," + MAX_IDENT + "})"
                    + "\\.prototype\\.get([A-Za-z0-9_$]{1," + MAX_IDENT + "})"
                    + "\\s*=\\s*function\\s*\\([^)]{0,200}\\)\\s*\\{"
                    + "[^{}]{0,400}?"
                    + "jspb\\.Message\\.get([A-Za-z0-9_$]{1,80})"
                    + "\\s*\\(\\s*this\\s*,\\s*(\\d{1,10})");

    private JsAnalyzer() {
    }

    /** Everything recovered from one script. */
    public static final class Result {
        private final List<GrpcEndpoint> endpoints;
        private final Map<String, List<GrpcMessageField>> messages;

        Result(List<GrpcEndpoint> endpoints, Map<String, List<GrpcMessageField>> messages) {
            this.endpoints = List.copyOf(endpoints);
            // Collections.unmodifiableMap, not Map.copyOf: Map.copyOf makes no ordering promise
            // and would discard the sort applied by analyze(), scrambling the results table.
            this.messages = Collections.unmodifiableMap(new LinkedHashMap<>(messages));
        }

        /** Discovered method paths, sorted and de-duplicated. */
        public List<GrpcEndpoint> endpoints() {
            return endpoints;
        }

        /** Discovered messages, each mapped to its fields in field-number order. */
        public Map<String, List<GrpcMessageField>> messages() {
            return messages;
        }

        public boolean isEmpty() {
            return endpoints.isEmpty() && messages.isEmpty();
        }

        public int fieldCount() {
            return messages.values().stream().mapToInt(List::size).sum();
        }
    }

    /**
     * Scans a script for endpoints and message definitions.
     *
     * @param javaScript the script body; {@code null} or oversized input yields an empty result
     */
    public static Result analyze(String javaScript) {
        if (javaScript == null || javaScript.isEmpty()
                || javaScript.length() > MAX_INPUT_LENGTH) {
            return new Result(List.of(), Map.of());
        }

        // Descriptors come first, because they are the only source of the message types; the
        // looser patterns then add any path a descriptor did not cover.
        Map<String, GrpcEndpoint> endpointsByPath = new LinkedHashMap<>();
        collectMethodDescriptors(javaScript, endpointsByPath);

        Matcher qualified = QUALIFIED_ENDPOINT.matcher(javaScript);
        while (qualified.find()) {
            record(endpointsByPath, new GrpcEndpoint(
                    qualified.group(1), qualified.group(2), qualified.group(3)));
        }
        collectUnqualifiedEndpoints(javaScript, endpointsByPath);

        TreeSet<GrpcEndpoint> endpoints = new TreeSet<>(endpointsByPath.values());

        // Setters are authoritative; getters only fill gaps, so setters are collected first.
        Map<String, TreeSet<GrpcMessageField>> byMessage = new LinkedHashMap<>();
        collectFields(SETTER, javaScript, byMessage);
        collectFields(GETTER, javaScript, byMessage);

        Map<String, List<GrpcMessageField>> messages = new LinkedHashMap<>();
        byMessage.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> messages.put(entry.getKey(), List.copyOf(entry.getValue())));

        return new Result(new ArrayList<>(endpoints), messages);
    }

    /** Reads every {@code MethodDescriptor}, recovering call style and message types. */
    private static void collectMethodDescriptors(
            String javaScript, Map<String, GrpcEndpoint> endpointsByPath) {

        Matcher matcher = METHOD_DESCRIPTOR.matcher(javaScript);
        while (matcher.find()) {
            record(endpointsByPath, new GrpcEndpoint(
                    matcher.group(1),
                    matcher.group(2),
                    matcher.group(3),
                    GrpcEndpoint.CallStyle.fromMethodType(matcher.group(4)),
                    matcher.group(5),
                    matcher.group(6)));
        }
    }

    /**
     * Finds package-less method paths by looking only just after a gRPC call site, so that an
     * ordinary two-segment URL elsewhere in the script is not reported as an endpoint.
     */
    private static void collectUnqualifiedEndpoints(
            String javaScript, Map<String, GrpcEndpoint> endpointsByPath) {

        Matcher callSite = GRPC_CALL_SITE.matcher(javaScript);
        while (callSite.find()) {
            int start = callSite.end();
            int end = Math.min(javaScript.length(), start + CALL_SITE_WINDOW);
            Matcher path = UNQUALIFIED_ENDPOINT.matcher(javaScript).region(start, end);
            while (path.find()) {
                record(endpointsByPath,
                        new GrpcEndpoint(path.group(1), path.group(2), path.group(3)));
            }
        }
    }

    /** Adds an endpoint, merging detail when the same path was already seen. */
    private static void record(Map<String, GrpcEndpoint> endpointsByPath, GrpcEndpoint endpoint) {
        endpointsByPath.merge(endpoint.path(), endpoint, GrpcEndpoint::mergedWith);
    }

    private static void collectFields(
            Pattern pattern, String javaScript, Map<String, TreeSet<GrpcMessageField>> byMessage) {

        Matcher matcher = pattern.matcher(javaScript);
        while (matcher.find()) {
            String messageName = matcher.group(1);
            String accessorName = matcher.group(2);
            String jspbAccessor = matcher.group(3);
            int fieldNumber;
            try {
                fieldNumber = Integer.parseInt(matcher.group(4));
            } catch (NumberFormatException e) {
                continue;
            }
            if (fieldNumber < 1) {
                continue;
            }

            TreeSet<GrpcMessageField> fields =
                    byMessage.computeIfAbsent(messageName, key -> new TreeSet<>());
            boolean alreadyKnown = fields.stream()
                    .anyMatch(field -> field.fieldNumber() == fieldNumber);
            if (alreadyKnown) {
                continue;
            }
            fields.add(new GrpcMessageField(
                    messageName,
                    toSnakeCase(stripListSuffix(accessorName, jspbAccessor)),
                    fieldNumber,
                    jspbAccessor,
                    inferType(jspbAccessor)));
        }
    }

    /**
     * Drops the {@code List} that protoc appends to a repeated field's accessor.
     *
     * <p>{@code repeated string roles} becomes {@code getRolesList}, so taking the accessor name
     * literally yields {@code roles_list} rather than the real field name {@code roles}. Only
     * stripped for accessors that are actually repeated, so a field genuinely named
     * {@code foo_list} is left alone.
     */
    static String stripListSuffix(String accessorName, String jspbAccessor) {
        boolean repeated = jspbAccessor.toLowerCase(Locale.ROOT).contains("repeated");
        if (repeated && accessorName.length() > 4 && accessorName.endsWith("List")) {
            return accessorName.substring(0, accessorName.length() - 4);
        }
        return accessorName;
    }

    /**
     * Maps a jspb accessor name onto a Type Definition tab type.
     *
     * <p>jspb collapses several protobuf types onto one accessor — {@code setProto3IntField} serves
     * {@code int32}, {@code int64}, {@code uint32} and enums alike — so the result names the
     * blackbox type that will decode the field, not necessarily the exact {@code .proto} type.
     */
    private static String inferType(String jspbAccessor) {
        String name = jspbAccessor.toLowerCase(Locale.ROOT);
        if (name.contains("string")) {
            return "string";
        }
        if (name.contains("bytes")) {
            return "bytes";
        }
        if (name.contains("bool")) {
            return "bool";
        }
        if (name.contains("float") || name.contains("double")) {
            return "double";
        }
        if (name.contains("int")) {
            return "int";
        }
        if (name.contains("wrapper") || name.contains("message")) {
            return "message";
        }
        if (name.contains("repeated")) {
            return "repeated";
        }
        return "?";
    }

    /**
     * Converts a JavaScript accessor suffix back to the {@code .proto} field name:
     * {@code UserName} becomes {@code user_name}. Runs of capitals are kept together so
     * {@code SetHTTPPort} becomes {@code http_port} rather than {@code h_t_t_p_port}.
     */
    static String toSnakeCase(String accessorName) {
        StringBuilder result = new StringBuilder(accessorName.length() + 8);
        for (int i = 0; i < accessorName.length(); i++) {
            char c = accessorName.charAt(i);
            boolean startsWord = i > 0
                    && Character.isUpperCase(c)
                    && (!Character.isUpperCase(accessorName.charAt(i - 1))
                        || (i + 1 < accessorName.length()
                            && Character.isLowerCase(accessorName.charAt(i + 1))));
            if (startsWord && result.length() > 0
                    && result.charAt(result.length() - 1) != '_') {
                result.append('_');
            }
            result.append(Character.toLowerCase(c));
        }
        return result.toString();
    }
}
