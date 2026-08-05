package com.recsys.infrastructure.k8s;

import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Reads Kustomize manifests as plain maps. Structural access only — every accessor returns a
 * null/empty neutral rather than throwing, so an assertion reports "no rule permits X" instead
 * of a NullPointerException that says nothing about the manifest.
 */
final class ManifestDocuments {

    private ManifestDocuments() {
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> allIn(Path dir) throws IOException {
        List<Map<String, Object>> all = new ArrayList<>();
        try (var files = Files.list(dir)) {
            List<Path> yamls = files.filter(p -> p.toString().endsWith(".yaml")).sorted().toList();
            for (Path p : yamls) {
                try (InputStream in = Files.newInputStream(p)) {
                    for (Object doc : new Yaml().loadAll(in)) {
                        if (doc instanceof Map<?, ?> map) all.add((Map<String, Object>) map);
                    }
                }
            }
        }
        return all;
    }

    static List<Map<String, Object>> ofKind(List<Map<String, Object>> docs, String kind) {
        return docs.stream().filter(d -> kind.equals(d.get("kind"))).toList();
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> mapAt(Map<String, Object> doc, String... path) {
        Map<String, Object> cursor = doc;
        for (String key : path) {
            Object next = cursor == null ? null : cursor.get(key);
            cursor = next instanceof Map<?, ?> m ? (Map<String, Object>) m : null;
        }
        return cursor;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> listOf(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof List<?> list ? (List<Map<String, Object>>) list : List.of();
    }

    static List<String> stringListOf(Map<String, Object> map, String key) {
        Object value = map == null ? null : map.get(key);
        return value instanceof List<?> list ? list.stream().map(String::valueOf).toList() : List.of();
    }

    static String nameOf(Map<String, Object> doc) {
        Map<String, Object> metadata = mapAt(doc, "metadata");
        return metadata == null ? null : String.valueOf(metadata.get("name"));
    }
}
