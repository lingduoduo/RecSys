package com.recsys.training.modelbased.twotower.service;

import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Service;

import java.nio.LongBuffer;
import java.util.Map;

@Service
public class UserTowerInferenceService {

    private final ModelArtifactLocator artifactLocator;
    private OrtEnvironment environment;
    private OrtSession session;

    public UserTowerInferenceService(ModelArtifactLocator artifactLocator) {
        this.artifactLocator = artifactLocator;
    }

    @PostConstruct
    public void init() throws OrtException, Exception {
        environment = OrtEnvironment.getEnvironment();
        try {
            session = environment.createSession(
                    artifactLocator.readModelBytes("user_tower.onnx"),
                    new OrtSession.SessionOptions()
            );
        } catch (IllegalStateException e) {
            throw new IllegalStateException("user_tower.onnx not found at "
                    + artifactLocator.describeModelLocation("user_tower.onnx")
                    + ". Run python-training/train_and_export.py first or point recsys.model.artifacts-dir to a pipeline output directory.", e);
        }
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
