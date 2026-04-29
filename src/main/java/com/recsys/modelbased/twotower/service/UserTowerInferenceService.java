package com.recsys.modelbased.twotower.service;

import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.util.Map;

@Service
public class UserTowerInferenceService {

    private final ModelArtifactLocator artifactLocator;
    private final String variant;
    private OrtEnvironment environment;
    private OrtSession session;
    private volatile boolean ready = false;

    @Autowired
    public UserTowerInferenceService(ModelArtifactLocator artifactLocator) {
        this(artifactLocator, "");
    }

    public UserTowerInferenceService(ModelArtifactLocator artifactLocator, String variant) {
        this.artifactLocator = artifactLocator;
        this.variant = variant == null ? "" : variant.trim();
    }

    @PostConstruct
    public void init() throws OrtException, Exception {
        environment = OrtEnvironment.getEnvironment();
        try {
            session = environment.createSession(
                    artifactLocator.readModelBytes(variant, "user_tower.onnx"),
                    new OrtSession.SessionOptions()
            );
            ready = true;
        } catch (IllegalStateException e) {
            throw new IllegalStateException("user_tower.onnx not found at "
                    + artifactLocator.describeModelLocation(variant, "user_tower.onnx")
                    + ". Set recsys.model.artifacts-dir to an external model directory, or place artifacts under classpath:artifacts/model/<variant>/.", e);
        }
    }

    public boolean isReady() {
        return ready;
    }

    public float[] inferUserEmbedding(FeatureEncoder.EncodedFeatures features) {
        try {
            long[] userArr = new long[]{features.getUserId()};
            try (OnnxTensor userTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(userArr), new long[]{1})) {
                Map<String, OnnxTensor> inputs = Map.of("user_id", userTensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    float[][] output = (float[][]) result.get("user_embedding").get().getValue();
                    return output[0];
                }
            }
        } catch (OrtException e) {
            throw new RuntimeException("Failed to run ONNX inference", e);
        }
    }

    @PreDestroy
    public void close() throws OrtException {
        try {
            if (session != null) session.close();
        } finally {
            if (environment != null) environment.close();
        }
    }
}
