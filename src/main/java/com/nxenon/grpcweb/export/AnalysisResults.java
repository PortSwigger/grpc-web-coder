package com.nxenon.grpcweb.export;

import com.nxenon.grpcweb.analyze.GrpcEndpoint;
import com.nxenon.grpcweb.analyze.GrpcMessageField;
import com.nxenon.grpcweb.analyze.JsAnalyzer;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

/**
 * Everything the analyzer has found so far, accumulated across runs.
 *
 * <p>Kept free of Swing so the export formats can be written and tested without a UI, and so the
 * results tables and the exporters read from one place rather than each keeping their own copy.
 *
 * <p>Not thread-safe: it is owned by the results panel and touched only on the event dispatch
 * thread.
 */
public final class AnalysisResults {

    /** One endpoint, with the response it was found in. */
    public record EndpointEntry(String source, GrpcEndpoint endpoint) {
    }

    /** One message field, with the response it was found in. */
    public record FieldEntry(String source, GrpcMessageField field) {
    }

    private final List<EndpointEntry> endpoints = new ArrayList<>();
    private final List<FieldEntry> fields = new ArrayList<>();

    /** Guards against listing the same finding twice when a file is analyzed repeatedly. */
    private final Set<String> seenEndpoints = new LinkedHashSet<>();
    private final Set<String> seenFields = new LinkedHashSet<>();

    /**
     * Adds one script's findings.
     *
     * @return how many rows were new; zero means everything had already been seen
     */
    public int add(String source, JsAnalyzer.Result result) {
        int added = 0;
        for (GrpcEndpoint endpoint : result.endpoints()) {
            if (seenEndpoints.add(source + " " + endpoint.path())) {
                endpoints.add(new EndpointEntry(source, endpoint));
                added++;
            }
        }
        for (Map.Entry<String, List<GrpcMessageField>> message : result.messages().entrySet()) {
            for (GrpcMessageField field : message.getValue()) {
                String key = source + " " + message.getKey() + " " + field.fieldNumber();
                if (seenFields.add(key)) {
                    fields.add(new FieldEntry(source, field));
                    added++;
                }
            }
        }
        return added;
    }

    public void clear() {
        endpoints.clear();
        fields.clear();
        seenEndpoints.clear();
        seenFields.clear();
    }

    public List<EndpointEntry> endpoints() {
        return Collections.unmodifiableList(endpoints);
    }

    public List<FieldEntry> fields() {
        return Collections.unmodifiableList(fields);
    }

    public boolean isEmpty() {
        return endpoints.isEmpty() && fields.isEmpty();
    }

    public int endpointCount() {
        return endpoints.size();
    }

    public int fieldCount() {
        return fields.size();
    }

    /** Distinct message names, sorted. */
    public List<String> messageNames() {
        return fields.stream()
                .map(entry -> entry.field().messageName())
                .distinct()
                .sorted()
                .toList();
    }

    /** Fields grouped by message name, each group sorted by field number. */
    public Map<String, List<GrpcMessageField>> fieldsByMessage() {
        Map<String, List<GrpcMessageField>> byMessage = new TreeMap<>();
        for (FieldEntry entry : fields) {
            byMessage.computeIfAbsent(entry.field().messageName(), key -> new ArrayList<>())
                    .add(entry.field());
        }
        byMessage.values().forEach(list ->
                list.sort(Comparator.comparingInt(GrpcMessageField::fieldNumber)));
        return byMessage;
    }

    /** The responses every message was found in, for attribution in the JSON export. */
    public Map<String, Set<String>> sourcesByMessage() {
        Map<String, Set<String>> sources = new LinkedHashMap<>();
        for (FieldEntry entry : fields) {
            sources.computeIfAbsent(entry.field().messageName(), key -> new LinkedHashSet<>())
                    .add(entry.source());
        }
        return sources;
    }

    /** Distinct endpoints, sorted by path, with duplicates from several sources collapsed. */
    public List<GrpcEndpoint> distinctEndpoints() {
        Map<String, GrpcEndpoint> byPath = new TreeMap<>();
        for (EndpointEntry entry : endpoints) {
            byPath.putIfAbsent(entry.endpoint().path(), entry.endpoint());
        }
        return List.copyOf(byPath.values());
    }
}
