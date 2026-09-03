package com.recsys.application.model;
import com.recsys.application.experiment.ModelVariants;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Unified locator for all model pipeline artifacts.
 *
 * Artifact types and their defaults:
 *   model  — classpath:artifacts/model/<variant>/  (feature configs, pre-computed embeddings, ONNX models)
 *   spark  — classpath:artifacts/pyspark/   (PySpark model directories and metadata)
 *
 * Each type has its own override env var so deployments can point to external
 * pipeline output directories without repackaging the JAR.
 */
@Service
public class ModelArtifactLocator {

    private static final String MODEL_CLASSPATH_DIR = "artifacts/model";
    private static final String MODEL_MANIFEST = "model_manifest.json";
    public static final String DEFAULT_VARIANT = "training";
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
        return openModel("", fileName);
    }

    public InputStream openModel(String variant, String fileName) throws IOException {
        String normalizedVariant = ModelVariants.trimOrEmpty(variant);
        return openModelStream(normalizedVariant, fileName);
    }

    public byte[] readModelBytes(String fileName) throws IOException {
        try (InputStream is = openModel(fileName)) {
            return is.readAllBytes();
        }
    }

    public byte[] readModelBytes(String variant, String fileName) throws IOException {
        try (InputStream is = openModel(variant, fileName)) {
            return is.readAllBytes();
        }
    }

    public Optional<ModelArtifactSnapshot> loadManifestSnapshot(String variant) {
        String normalizedVariant = ModelVariants.trimOrEmpty(variant);
        byte[] manifestBytes = readStrictVariantFileIfPresent(normalizedVariant, MODEL_MANIFEST);
        if (manifestBytes == null) {
            return Optional.empty();
        }

        try {
            ModelArtifactManifest manifest = ModelArtifactManifest.parse(manifestBytes);
            ModelArtifactManifest.ModelContract contract = manifest.validate();
            Map<String, byte[]> files = new HashMap<>();
            for (Map.Entry<String, String> checksum : manifest.sha256().entrySet()) {
                byte[] bytes = readStrictVariantFile(normalizedVariant, checksum.getKey());
                verifyChecksum(checksum.getKey(), bytes, checksum.getValue());
                files.put(checksum.getKey(), bytes);
            }
            byte[] featureConfig = files.get("feature_config.json");
            manifest.validateFeatureVersion(featureConfig);
            Map<String, byte[]> companions = new HashMap<>(files);
            companions.remove("feature_config.json");
            companions.remove(manifest.modelFile());
            return Optional.of(new ModelArtifactSnapshot(
                    featureConfig,
                    files.get(manifest.modelFile()),
                    manifest.modelFile(),
                    manifest.modelVersion(),
                    contract,
                    companions
            ));
        } catch (IllegalStateException e) {
            throw new IllegalStateException("Invalid " + MODEL_MANIFEST + " for variant '"
                    + effectiveVariant(normalizedVariant) + "': " + e.getMessage(), e);
        } catch (IOException e) {
            throw new IllegalStateException("Cannot parse " + MODEL_MANIFEST + " for variant '"
                    + effectiveVariant(normalizedVariant) + "' due to malformed or duplicate content: "
                    + e.getMessage(), e);
        }
    }

    public String describeModelLocation(String fileName) {
        return describeModelLocation("", fileName);
    }

    public String describeModelLocation(String variant, String fileName) {
        String normalizedVariant = ModelVariants.trimOrEmpty(variant);
        if (!modelDir.isBlank()) {
            if (!normalizedVariant.isBlank()) {
                return Path.of(modelDir).resolve(normalizedVariant).resolve(fileName).normalize().toAbsolutePath()
                        + " (fallback " + Path.of(modelDir).resolve(fileName).normalize().toAbsolutePath() + ")";
            }
            return Path.of(modelDir).resolve(fileName).normalize().toAbsolutePath().toString();
        }
        String effectiveVariant = normalizedVariant.isBlank() ? DEFAULT_VARIANT : normalizedVariant;
        return "classpath:" + MODEL_CLASSPATH_DIR + "/" + effectiveVariant + "/" + fileName;
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
        ClassPathResource resource = new ClassPathResource(SPARK_CLASSPATH_DIR + "/" + relativePath);
        try {
            // A "file:" URL means the resource is exploded on disk (local dev / mvn runs)
            // and can be exposed as a real filesystem Path. Inside a jar the URL is
            // "jar:"/nested and has no filesystem Path — getFile() would throw there, so we
            // never call it; we fall through to the actionable error below instead.
            URL url = resource.getURL();
            if ("file".equals(url.getProtocol())) {
                return Path.of(url.toURI()).normalize();
            }
        } catch (IOException | URISyntaxException e) {
            throw new IllegalStateException(sparkPathUnavailableMessage(relativePath), e);
        }
        throw new IllegalStateException(sparkPathUnavailableMessage(relativePath));
    }

    private static String sparkPathUnavailableMessage(String relativePath) {
        return "Cannot resolve filesystem path for classpath:" + SPARK_CLASSPATH_DIR + "/" + relativePath
                + " (packaged in a JAR has no filesystem Path). "
                + "Set recsys.spark.artifacts-dir to an external directory when running from a JAR.";
    }

    public String describeSparkLocation(String relativePath) {
        return describe(SPARK_CLASSPATH_DIR, sparkDir, relativePath);
    }

    // ---- private helpers ----

    private InputStream openModelStream(String variant, String fileName) throws IOException {
        if (!modelDir.isBlank()) {
            Path baseDir = Path.of(modelDir).toAbsolutePath().normalize();
            if (!variant.isBlank()) {
                Path variantPath = Path.of(modelDir).resolve(variant).resolve(fileName).normalize();
                if (!variantPath.toAbsolutePath().startsWith(baseDir)) {
                    throw new IllegalStateException("Illegal path traversal attempt: " + variantPath);
                }
                if (Files.exists(variantPath)) {
                    return Files.newInputStream(variantPath);
                }
            }
            Path flatPath = Path.of(modelDir).resolve(fileName).normalize();
            if (!flatPath.toAbsolutePath().startsWith(baseDir)) {
                throw new IllegalStateException("Illegal path traversal attempt: " + flatPath);
            }
            if (Files.exists(flatPath)) {
                return Files.newInputStream(flatPath);
            }
            throw new IllegalStateException("artifact not found: " + describeModelLocation(variant, fileName));
        }

        String effectiveVariant = variant.isBlank() ? DEFAULT_VARIANT : variant;
        ClassPathResource variantResource = new ClassPathResource(MODEL_CLASSPATH_DIR + "/" + effectiveVariant + "/" + fileName);
        if (variantResource.exists()) {
            return variantResource.getInputStream();
        }

        ClassPathResource rootResource = new ClassPathResource(fileName);
        if (rootResource.exists()) {
            return rootResource.getInputStream();
        }

        throw new IllegalStateException(describeModelLocation(variant, fileName) + " not found. "
                + "Run the training pipeline or set recsys.model.artifacts-dir to an external model directory.");
    }

    private byte[] readStrictVariantFileIfPresent(String variant, String fileName) {
        if (!modelDir.isBlank()) {
            Path variantDir = strictExternalVariantDir(variant);
            if (variantDir == null) {
                return null;
            }
            Path candidate = variantDir.resolve(fileName);
            if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            if (Files.isSymbolicLink(candidate)) {
                throw new IllegalStateException("manifest artifact must not be a symbolic link: " + fileName);
            }
            if (!Files.isRegularFile(candidate, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("manifest artifact is not a regular file: " + fileName);
            }
            try {
                Path realCandidate = candidate.toRealPath(LinkOption.NOFOLLOW_LINKS);
                if (!realCandidate.startsWith(variantDir)) {
                    throw new IllegalStateException("manifest artifact escapes variant directory: " + fileName);
                }
                try (InputStream input = Files.newInputStream(realCandidate, LinkOption.NOFOLLOW_LINKS)) {
                    return input.readAllBytes();
                }
            } catch (IOException e) {
                throw new IllegalStateException("cannot read " + fileName + " from " + variantDir, e);
            }
        }

        ClassPathResource resource = strictClasspathResource(variant, fileName);
        if (!resource.exists()) {
            return null;
        }
        try (InputStream input = resource.getInputStream()) {
            return input.readAllBytes();
        } catch (IOException e) {
            throw new IllegalStateException("cannot read " + fileName + " for variant '"
                    + effectiveVariant(variant) + "'", e);
        }
    }

    private byte[] readStrictVariantFile(String variant, String fileName) {
        byte[] bytes = readStrictVariantFileIfPresent(variant, fileName);
        if (bytes == null) {
            throw new IllegalStateException("manifest artifact not found: " + fileName);
        }
        return bytes;
    }

    private Path strictExternalVariantDir(String variant) {
        Path configuredBase = Path.of(modelDir).toAbsolutePath().normalize();
        if (!Files.exists(configuredBase)) {
            return null;
        }
        try {
            Path realBase = configuredBase.toRealPath();
            if (!Files.isDirectory(realBase, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("model artifacts directory is not a directory: " + configuredBase);
            }

            Path requestedVariant = variant.isBlank() ? realBase : realBase.resolve(variant).normalize();
            if (!requestedVariant.startsWith(realBase)) {
                throw new IllegalStateException("Illegal variant path traversal attempt: " + variant);
            }
            if (!Files.exists(requestedVariant, LinkOption.NOFOLLOW_LINKS)) {
                return null;
            }
            Path realVariant = requestedVariant.toRealPath();
            if (!realVariant.startsWith(realBase)) {
                throw new IllegalStateException("variant directory escapes model artifacts directory: " + variant);
            }
            if (!Files.isDirectory(realVariant, LinkOption.NOFOLLOW_LINKS)) {
                throw new IllegalStateException("variant path is not a directory: " + variant);
            }
            return realVariant;
        } catch (IOException e) {
            throw new IllegalStateException("cannot resolve model artifact directory for variant '"
                    + effectiveVariant(variant) + "'", e);
        }
    }

    private ClassPathResource strictClasspathResource(String variant, String fileName) {
        return new ClassPathResource(MODEL_CLASSPATH_DIR + "/" + effectiveVariant(variant) + "/" + fileName);
    }

    private static String effectiveVariant(String variant) {
        return variant.isBlank() ? DEFAULT_VARIANT : variant;
    }

    private static void verifyChecksum(String fileName, byte[] bytes, String expectedHex) {
        try {
            byte[] expected = java.util.HexFormat.of().parseHex(expectedHex);
            byte[] actual = MessageDigest.getInstance("SHA-256").digest(bytes);
            if (!MessageDigest.isEqual(expected, actual)) {
                throw new IllegalStateException("SHA-256 checksum mismatch for " + fileName);
            }
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private InputStream openStream(String classpathBase, String externalDir, String path) throws IOException {
        if (!externalDir.isBlank()) {
            Path baseDir = Path.of(externalDir).toAbsolutePath().normalize();
            Path p = Path.of(externalDir).resolve(path).normalize();
            if (!p.toAbsolutePath().startsWith(baseDir)) {
                throw new IllegalStateException("Illegal path traversal attempt: " + p);
            }
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
