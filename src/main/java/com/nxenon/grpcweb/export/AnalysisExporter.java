package com.nxenon.grpcweb.export;

import com.google.gson.GsonBuilder;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.nxenon.grpcweb.analyze.GrpcEndpoint;
import com.nxenon.grpcweb.analyze.GrpcMessageField;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;

/**
 * Renders analysis results in the formats a tester actually needs next.
 *
 * <p>Findings are only useful once they leave Burp: into a report, into a spreadsheet, into
 * {@code grpcurl}, or back into this extension's own Type Definition tab. Each format here answers
 * one of those.
 */
public final class AnalysisExporter {

    /** The available export formats. */
    public enum Format {
        JSON("JSON (everything)", "json",
                "Every endpoint and message field, with the response each was found in."),
        ENDPOINTS_CSV("Endpoints (CSV)", "csv",
                "One row per endpoint, for a spreadsheet or a report table."),
        FIELDS_CSV("Message fields (CSV)", "csv",
                "One row per message field, for a spreadsheet or a report table."),
        PROTO("Protobuf schema (.proto)", "proto",
                "A reconstructed .proto, for protoc or grpcurl."),
        TYPE_DEFINITIONS("Type definitions (JSON)", "json",
                "Paste a message's object into the Type Definition tab to decode with real names.");

        private final String label;
        private final String extension;
        private final String description;

        Format(String label, String extension, String description) {
            this.label = label;
            this.extension = extension;
            this.description = description;
        }

        public String label() {
            return label;
        }

        /** Default file extension, without the dot. */
        public String extension() {
            return extension;
        }

        public String description() {
            return description;
        }

        /** A sensible default filename for a save dialog. */
        public String defaultFileName() {
            return switch (this) {
                case JSON -> "grpc-web-analysis.json";
                case ENDPOINTS_CSV -> "grpc-web-endpoints.csv";
                case FIELDS_CSV -> "grpc-web-message-fields.csv";
                case PROTO -> "grpc-web-reconstructed.proto";
                case TYPE_DEFINITIONS -> "grpc-web-type-definitions.json";
            };
        }

        @Override
        public String toString() {
            return label;
        }
    }

    private AnalysisExporter() {
    }

    /** Renders results in the given format. */
    public static String export(AnalysisResults results, Format format) {
        return switch (format) {
            case JSON -> toJson(results);
            case ENDPOINTS_CSV -> toEndpointsCsv(results);
            case FIELDS_CSV -> toFieldsCsv(results);
            case PROTO -> toProto(results);
            case TYPE_DEFINITIONS -> toTypeDefinitions(results);
        };
    }

    // ------------------------------------------------------------------ JSON

    static String toJson(AnalysisResults results) {
        JsonObject root = new JsonObject();

        JsonArray endpoints = new JsonArray();
        for (AnalysisResults.EndpointEntry entry : results.endpoints()) {
            GrpcEndpoint endpoint = entry.endpoint();
            JsonObject object = new JsonObject();
            object.addProperty("source", entry.source());
            object.addProperty("path", endpoint.path());
            object.addProperty("service", endpoint.service());
            object.addProperty("method", endpoint.method());
            object.addProperty("callStyle", endpoint.callStyle().label());
            if (endpoint.hasMessageTypes()) {
                object.addProperty("requestType", endpoint.requestType());
                object.addProperty("responseType", endpoint.responseType());
            }
            endpoints.add(object);
        }
        root.add("endpoints", endpoints);

        Map<String, Set<String>> sources = results.sourcesByMessage();
        JsonArray messages = new JsonArray();
        for (Map.Entry<String, List<GrpcMessageField>> entry
                : results.fieldsByMessage().entrySet()) {
            JsonObject message = new JsonObject();
            message.addProperty("name", entry.getKey());

            JsonArray messageSources = new JsonArray();
            sources.getOrDefault(entry.getKey(), Set.of()).forEach(messageSources::add);
            message.add("sources", messageSources);

            JsonArray fields = new JsonArray();
            for (GrpcMessageField field : entry.getValue()) {
                JsonObject object = new JsonObject();
                object.addProperty("name", field.fieldName());
                object.addProperty("number", field.fieldNumber());
                object.addProperty("type", field.inferredType());
                object.addProperty("jspbAccessor", field.jspbSetter());
                fields.add(object);
            }
            message.add("fields", fields);
            messages.add(message);
        }
        root.add("messages", messages);

        return new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create()
                .toJson(root);
    }

    // ------------------------------------------------------------------- CSV

    static String toEndpointsCsv(AnalysisResults results) {
        StringBuilder csv = new StringBuilder();
        csv.append("source,path,service,method,call_style,request_type,response_type\n");
        for (AnalysisResults.EndpointEntry entry : results.endpoints()) {
            GrpcEndpoint endpoint = entry.endpoint();
            appendCsvRow(csv,
                    entry.source(),
                    endpoint.path(),
                    endpoint.service(),
                    endpoint.method(),
                    endpoint.callStyle().label(),
                    endpoint.requestType(),
                    endpoint.responseType());
        }
        return csv.toString();
    }

    static String toFieldsCsv(AnalysisResults results) {
        StringBuilder csv = new StringBuilder();
        csv.append("source,message,field,number,type,jspb_accessor\n");
        for (AnalysisResults.FieldEntry entry : results.fields()) {
            GrpcMessageField field = entry.field();
            appendCsvRow(csv,
                    entry.source(),
                    field.messageName(),
                    field.fieldName(),
                    String.valueOf(field.fieldNumber()),
                    field.inferredType(),
                    field.jspbSetter());
        }
        return csv.toString();
    }

    private static void appendCsvRow(StringBuilder csv, String... values) {
        for (int i = 0; i < values.length; i++) {
            if (i > 0) {
                csv.append(',');
            }
            csv.append(csvField(values[i]));
        }
        csv.append('\n');
    }

    /**
     * Quotes a CSV field per RFC 4180.
     *
     * <p>Values come from a target's JavaScript, so they can contain commas, quotes and newlines.
     * A field starting with {@code =}, {@code +}, {@code -} or {@code @} is also prefixed with an
     * apostrophe: spreadsheets treat those as formulas, and a crafted field name would otherwise
     * execute when the tester opens their own report.
     */
    private static String csvField(String value) {
        String safe = value == null ? "" : value;
        if (!safe.isEmpty() && "=+-@\t\r".indexOf(safe.charAt(0)) >= 0) {
            safe = "'" + safe;
        }
        if (safe.indexOf(',') >= 0 || safe.indexOf('"') >= 0
                || safe.indexOf('\n') >= 0 || safe.indexOf('\r') >= 0) {
            return '"' + safe.replace("\"", "\"\"") + '"';
        }
        return safe;
    }

    // ----------------------------------------------------------------- proto

    /**
     * Reconstructs a {@code .proto} from the discovered messages and services.
     *
     * <p>Field names and numbers are recovered exactly. Types are the closest match to the jspb
     * accessor, which cannot distinguish {@code int32} from {@code int64}, {@code uint32} or an
     * enum, so integers become {@code int64}. Messages that a service references but whose fields
     * were never seen are emitted as empty stubs, so the result compiles.
     *
     * <p>A {@code .proto} declares one package, so when findings span several packages each is
     * emitted as its own complete section behind a marker comment, to be split into separate files.
     */
    static String toProto(AnalysisResults results) {
        Map<String, List<GrpcMessageField>> byMessage = results.fieldsByMessage();
        List<GrpcEndpoint> endpoints = results.distinctEndpoints();

        // Group messages and services by protobuf package.
        Map<String, PackageContents> packages = new TreeMap<>();
        for (Map.Entry<String, List<GrpcMessageField>> entry : byMessage.entrySet()) {
            String qualified = entry.getKey();
            packages.computeIfAbsent(packageOf(qualified), key -> new PackageContents())
                    .messages.put(simpleNameOf(qualified), entry.getValue());
        }
        for (GrpcEndpoint endpoint : endpoints) {
            PackageContents contents =
                    packages.computeIfAbsent(packageOf(endpoint.service()), key -> new PackageContents());
            contents.services
                    .computeIfAbsent(simpleNameOf(endpoint.service()), key -> new ArrayList<>())
                    .add(endpoint);
            // Any message a service names must exist for the file to compile.
            if (endpoint.hasMessageTypes()) {
                contents.referenced.add(endpoint.requestType());
                contents.referenced.add(endpoint.responseType());
            }
        }

        if (packages.isEmpty()) {
            return "// No gRPC-Web definitions have been analyzed yet.\n";
        }

        StringBuilder proto = new StringBuilder();
        boolean multiplePackages = packages.size() > 1;
        if (multiplePackages) {
            proto.append("// Findings span ").append(packages.size())
                    .append(" protobuf packages. A .proto file declares one package, so each\n")
                    .append("// section below is a complete file: split them at the markers.\n\n");
        }

        boolean first = true;
        for (Map.Entry<String, PackageContents> entry : packages.entrySet()) {
            if (!first) {
                proto.append('\n');
            }
            first = false;
            if (multiplePackages) {
                String fileName = entry.getKey().isEmpty() ? "root" : entry.getKey();
                proto.append("// ===== file: ").append(fileName).append(".proto =====\n\n");
            }
            appendPackage(proto, entry.getKey(), entry.getValue(), byMessage.keySet());
        }
        return proto.toString();
    }

    /** Messages and services belonging to one protobuf package. */
    private static final class PackageContents {
        private final Map<String, List<GrpcMessageField>> messages = new TreeMap<>();
        private final Map<String, List<GrpcEndpoint>> services = new TreeMap<>();
        private final Set<String> referenced = new TreeSet<>();
    }

    private static void appendPackage(
            StringBuilder proto, String packageName, PackageContents contents,
            Set<String> allKnownMessages) {

        proto.append("syntax = \"proto3\";\n\n");
        if (!packageName.isEmpty()) {
            proto.append("package ").append(packageName).append(";\n\n");
        }
        proto.append("// Reconstructed by gRPC-Web Coder from generated JavaScript.\n")
                .append("// Field names and numbers are recovered exactly. Types are the closest\n")
                .append("// match to the jspb accessor, which does not distinguish int32 from\n")
                .append("// int64, uint32 or an enum, so integers are written as int64.\n\n");

        for (Map.Entry<String, List<GrpcMessageField>> message : contents.messages.entrySet()) {
            proto.append("message ").append(message.getKey()).append(" {\n");
            for (GrpcMessageField field : message.getValue()) {
                appendField(proto, field);
            }
            proto.append("}\n\n");
        }

        // Stubs for messages a service references but whose fields were never found.
        for (String referenced : contents.referenced) {
            if (allKnownMessages.contains(referenced)) {
                continue;
            }
            if (!packageOf(referenced).equals(packageName)) {
                continue;
            }
            proto.append("// Referenced by a service below, but no fields for it were found.\n")
                    .append("message ").append(simpleNameOf(referenced)).append(" {}\n\n");
        }

        for (Map.Entry<String, List<GrpcEndpoint>> service : contents.services.entrySet()) {
            proto.append("service ").append(service.getKey()).append(" {\n");
            for (GrpcEndpoint endpoint : service.getValue()) {
                appendRpc(proto, endpoint, packageName);
            }
            proto.append("}\n");
        }
    }

    private static void appendField(StringBuilder proto, GrpcMessageField field) {
        String protoType = protoTypeFor(field.inferredType());
        if (protoType == null) {
            // The accessor did not reveal the type. Commented out so the file still compiles,
            // while the name and number stay on record.
            proto.append("  // ").append(field.fieldName()).append(" = ")
                    .append(field.fieldNumber())
                    .append(";  // type not recoverable from the JavaScript (")
                    .append(field.jspbSetter()).append(")\n");
            return;
        }
        proto.append("  ").append(protoType).append(' ')
                .append(field.fieldName()).append(" = ")
                .append(field.fieldNumber()).append(";\n");
    }

    private static void appendRpc(StringBuilder proto, GrpcEndpoint endpoint, String packageName) {
        if (!endpoint.hasMessageTypes()) {
            proto.append("  // rpc ").append(endpoint.method())
                    .append(" -- request and response types were not found in the JavaScript\n");
            return;
        }
        proto.append("  rpc ").append(endpoint.method()).append(" (")
                .append(endpoint.callStyle().isRequestStreaming() ? "stream " : "")
                .append(relativeName(endpoint.requestType(), packageName))
                .append(") returns (")
                .append(endpoint.callStyle().isResponseStreaming() ? "stream " : "")
                .append(relativeName(endpoint.responseType(), packageName))
                .append(");\n");
    }

    /**
     * Maps an analyzer type onto a protobuf type.
     *
     * @return the protobuf type, or {@code null} when the JavaScript did not reveal one
     */
    private static String protoTypeFor(String inferredType) {
        return switch (inferredType) {
            case "string" -> "string";
            case "bytes" -> "bytes";
            case "bool" -> "bool";
            case "int" -> "int64";
            case "double" -> "double";
            // "message" is known to be a message but not which one; "repeated" and "?" say even
            // less. None can be written as a valid field declaration.
            default -> null;
        };
    }

    /** Drops the package prefix when it matches the file's own package. */
    private static String relativeName(String qualifiedName, String packageName) {
        if (!packageName.isEmpty() && packageOf(qualifiedName).equals(packageName)) {
            return simpleNameOf(qualifiedName);
        }
        return qualifiedName;
    }

    private static String packageOf(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? "" : qualifiedName.substring(0, lastDot);
    }

    private static String simpleNameOf(String qualifiedName) {
        int lastDot = qualifiedName.lastIndexOf('.');
        return lastDot < 0 ? qualifiedName : qualifiedName.substring(lastDot + 1);
    }

    // ------------------------------------------------------- type definitions

    /**
     * Renders a Type Definition document per message, keyed by message name.
     *
     * <p>Copying one message's object into the Type Definition tab makes a decoded payload read
     * with real field names instead of bare numbers, which is the whole point of running the
     * analyzer before touching a payload.
     *
     * <p>Fields whose type the JavaScript does not reveal are left out rather than guessed. The
     * decoder infers any field absent from a definition, so an omission costs the name but never
     * produces a wrong type.
     */
    static String toTypeDefinitions(AnalysisResults results) {
        JsonObject root = new JsonObject();
        for (Map.Entry<String, List<GrpcMessageField>> entry
                : results.fieldsByMessage().entrySet()) {

            JsonObject typeDefinition = new JsonObject();
            for (GrpcMessageField field : entry.getValue()) {
                String type = typeDefinitionTypeFor(field.inferredType());
                if (type == null) {
                    continue;
                }
                JsonObject definition = new JsonObject();
                definition.addProperty("type", type);
                definition.addProperty("name", field.fieldName());
                if ("message".equals(type)) {
                    // The nested shape is unknown; an empty definition lets the decoder infer it.
                    definition.add("message_typedef", new JsonObject());
                }
                typeDefinition.add(String.valueOf(field.fieldNumber()), definition);
            }
            root.add(entry.getKey(), typeDefinition);
        }
        return new GsonBuilder()
                .setPrettyPrinting()
                .disableHtmlEscaping()
                .create()
                .toJson(root);
    }

    /**
     * Maps an analyzer type onto a Type Definition tab type.
     *
     * @return the type name, or {@code null} when it cannot be mapped confidently
     */
    private static String typeDefinitionTypeFor(String inferredType) {
        return switch (inferredType) {
            case "string" -> "string";
            case "bytes" -> "bytes";
            case "bool" -> "bool";
            case "int" -> "int";
            case "double" -> "double";
            case "message" -> "message";
            // "repeated" does not say what it repeats, and "?" says nothing at all.
            default -> null;
        };
    }

    /** Groups results by message for callers that want per-message type definitions. */
    static Map<String, String> typeDefinitionsByMessage(AnalysisResults results) {
        Map<String, String> byMessage = new LinkedHashMap<>();
        com.google.gson.JsonObject all =
                com.google.gson.JsonParser.parseString(toTypeDefinitions(results))
                        .getAsJsonObject();
        com.google.gson.Gson pretty =
                new GsonBuilder().setPrettyPrinting().disableHtmlEscaping().create();
        for (String message : all.keySet()) {
            byMessage.put(message, pretty.toJson(all.get(message)));
        }
        return byMessage;
    }
}
