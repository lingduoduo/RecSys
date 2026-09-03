package com.recsys.application.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonFactory;
import com.fasterxml.jackson.core.StreamReadFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.util.Map;
import java.util.Set;

public record ModelArtifactManifest(
        @JsonProperty("schema_version") int schemaVersion,
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("model_file") String modelFile,
        @JsonProperty("sha256") Map<String, String> sha256,
        @JsonProperty("inputs") Map<String, TensorSpec> inputs,
        @JsonProperty("output") OutputSpec output
) {

    private static final int SUPPORTED_SCHEMA_VERSION = 1;
    private static final Set<String> REQUIRED_INPUTS = Set.of("user_id", "item_id");
    private static final ObjectMapper MAPPER = new ObjectMapper(JsonFactory.builder()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .build());

    public ModelArtifactManifest {
        sha256 = sha256 == null ? null : Map.copyOf(sha256);
        inputs = inputs == null ? null : Map.copyOf(inputs);
    }

    public static ModelArtifactManifest parse(byte[] json) throws IOException {
        return MAPPER.readValue(json, ModelArtifactManifest.class);
    }

    public ModelContract validate() {
        if (schemaVersion != SUPPORTED_SCHEMA_VERSION) {
            throw new IllegalStateException("unsupported schema_version " + schemaVersion
                    + "; expected " + SUPPORTED_SCHEMA_VERSION);
        }
        requireText(modelVersion, "model_version");
        validateSimpleFileName(modelFile, "model_file");

        if (sha256 == null) {
            throw new IllegalStateException("sha256 must be an object");
        }
        for (Map.Entry<String, String> entry : sha256.entrySet()) {
            validateSimpleFileName(entry.getKey(), "sha256 filename");
            String checksum = entry.getValue();
            if (checksum == null || !checksum.matches("[0-9a-f]{64}")) {
                throw new IllegalStateException("invalid SHA-256 checksum for " + entry.getKey()
                        + "; expected 64 lowercase hex characters");
            }
        }
        requireChecksum("feature_config.json");
        requireChecksum(modelFile);

        if (inputs == null) {
            throw new IllegalStateException("inputs must be an object containing user_id and item_id");
        }
        for (String inputName : REQUIRED_INPUTS) {
            if (!inputs.containsKey(inputName)) {
                throw new IllegalStateException("required input " + inputName + " is missing");
            }
        }
        for (String inputName : inputs.keySet()) {
            if (!REQUIRED_INPUTS.contains(inputName)) {
                throw new IllegalStateException("unexpected required input " + inputName);
            }
        }

        TensorSpec user = validateInput("user_id", inputs.get("user_id"));
        validateInput("item_id", inputs.get("item_id"));
        if (output == null) {
            throw new IllegalStateException("output must be present");
        }
        requireText(output.name(), "output name");
        TensorType outputType = parseType(output.type(), "output " + output.name());
        if (outputType != TensorType.FLOAT) {
            throw new IllegalStateException("output " + output.name() + " must use FLOAT, got " + outputType);
        }
        validateRank(output.rank(), "output " + output.name());

        return new ModelContract("user_id", "item_id", TensorType.INT64, user.rank(),
                output.name(), outputType, output.rank());
    }

    public void validateFeatureVersion(byte[] featureConfig) {
        try {
            JsonNode config = MAPPER.readTree(featureConfig);
            JsonNode versionNode = config.get("model_version");
            String featureVersion = versionNode == null || versionNode.isNull() ? null : versionNode.asText();
            if (!modelVersion.equals(featureVersion)) {
                throw new IllegalStateException("manifest model_version " + modelVersion
                        + " does not match feature_config.json model_version " + featureVersion);
            }
        } catch (IOException e) {
            throw new IllegalStateException("cannot parse feature_config.json", e);
        }
    }

    private TensorSpec validateInput(String name, TensorSpec spec) {
        if (spec == null) {
            throw new IllegalStateException("required input " + name + " must define type and rank");
        }
        TensorType type = parseType(spec.type(), "input " + name);
        if (type != TensorType.INT64) {
            throw new IllegalStateException("input " + name + " must use INT64, got " + type);
        }
        validateRank(spec.rank(), "input " + name);
        return spec;
    }

    private static TensorType parseType(String raw, String field) {
        try {
            return TensorType.valueOf(raw == null ? "" : raw);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(field + " has unsupported tensor type " + raw, e);
        }
    }

    private static void validateRank(int rank, String field) {
        if (rank != 1) {
            throw new IllegalStateException(field + " rank must be 1, got " + rank);
        }
    }

    private void requireChecksum(String fileName) {
        if (!sha256.containsKey(fileName)) {
            throw new IllegalStateException("missing SHA-256 checksum for " + fileName);
        }
    }

    static void validateSimpleFileName(String value, String field) {
        requireText(value, field);
        if (value.equals(".") || value.equals("..") || value.contains("/") || value.contains("\\")) {
            throw new IllegalStateException(field + " must be a simple relative filename: " + value);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalStateException(field + " must not be blank");
        }
    }

    public enum TensorType {
        INT64,
        FLOAT
    }

    public record TensorSpec(String type, int rank) {
    }

    public record OutputSpec(String name, String type, int rank) {
    }

    public record ModelContract(
            String userInput,
            String itemInput,
            TensorType inputType,
            int inputRank,
            String outputName,
            TensorType outputType,
            int outputRank
    ) {
        public static ModelContract legacy() {
            return new ModelContract("user_id", "item_id", TensorType.INT64, 1,
                    "score", TensorType.FLOAT, 1);
        }
    }
}
