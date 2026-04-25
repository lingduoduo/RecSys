package com.recsys.modelbased.twotower.service;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Unified locator for all model pipeline artifacts.
 *
 * Artifact types and their defaults:
 *   model  — classpath:model/              (feature configs, pre-computed embeddings, ONNX models)
 *   spark  — classpath:artifacts/pyspark/  (PySpark model directories and metadata)
 *
 * Each type has its own override env var so deployments can point to external
 * pipeline output directories without repackaging the JAR.
 */
@Service
public class ModelArtifactLocator {

    private static final String MODEL_CLASSPATH_DIR = "model";
    private static final String SPARK_CLASSPATH_DIR = "artifacts/pyspark";

    private final String modelDir;
    private final String sparkDir;

    public ModelArtifactLocator(
            @Value("${recsys.model.artifacts-dir:}") String modelDir,
            @Value("${recsys.spark.artifacts-dir:}") String sparkDir) {
        this.modelDir = trim(modelDir);
        this.sparkDir = trim(sparkDir);
    }

    // ---- model artifacts (feature configs, embeddings, ONNX models) ----

    public InputStream openModel(String fileName) throws IOException {
        return openStream(MODEL_CLASSPATH_DIR, modelDir, fileName);
    }

    public byte[] readModelBytes(String fileName) throws IOException {
        try (InputStream is = openModel(fileName)) {
            return is.readAllBytes();
        }
    }

    public String describeModelLocation(String fileName) {
        return describe(MODEL_CLASSPATH_DIR, modelDir, fileName);
    }

    // ---- spark artifacts (PySpark model files and directories) ----

    public InputStream openSpark(String relativePath) throws IOException {
        return openStream(SPARK_CLASSPATH_DIR, sparkDir, relativePath);
    }

    public byte[] readSparkBytes(String relativePath) throws IOException {
        try (InputStream is = openSpark(relativePath)) {
            return is.readAllBytes();
        }
    }

    /**
     * Returns a filesystem Path for a Spark artifact. For directory-based artifacts
     * (e.g. parquet model dirs) this must be a real path; set recsys.spark.artifacts-dir
     * when running from a JAR.
     */
    public Path resolveSparkPath(String relativePath) {
        if (!sparkDir.isBlank()) {
            return Path.of(sparkDir).resolve(relativePath).normalize();
        }
        try {
            return new ClassPathResource(SPARK_CLASSPATH_DIR + "/" + relativePath).getFile().toPath();
        } catch (IOException e) {
            throw new IllegalStateException(
                    "Cannot resolve filesystem path for classpath:" + SPARK_CLASSPATH_DIR + "/" + relativePath
                    + ". Set recsys.spark.artifacts-dir when running from a JAR.", e);
        }
    }

    public String describeSparkLocation(String relativePath) {
        return describe(SPARK_CLASSPATH_DIR, sparkDir, relativePath);
    }

    // ---- private helpers ----

    private InputStream openStream(String classpathBase, String externalDir, String path) throws IOException {
        if (!externalDir.isBlank()) {
            Path p = Path.of(externalDir).resolve(path).normalize();
            if (!Files.exists(p)) {
                throw new IllegalStateException("artifact not found: " + p.toAbsolutePath());
            }
            return Files.newInputStream(p);
        }
        ClassPathResource resource = new ClassPathResource(classpathBase + "/" + path);
        if (!resource.exists()) {
            throw new IllegalStateException(classpathBase + "/" + path + " not found on classpath. "
                    + "Run the training pipeline or set the corresponding artifacts-dir property.");
        }
        return resource.getInputStream();
    }

    private String describe(String classpathBase, String externalDir, String path) {
        if (!externalDir.isBlank()) {
            return Path.of(externalDir).resolve(path).normalize().toAbsolutePath().toString();
        }
        return "classpath:" + classpathBase + "/" + path;
    }

    private static String trim(String val) {
        return val == null ? "" : val.trim();
    }
}
