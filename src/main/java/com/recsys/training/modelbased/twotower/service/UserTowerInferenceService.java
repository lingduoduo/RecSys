package com.recsys.training.modelbased.twotower.service;

import ai.onnxruntime.*;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.LongBuffer;
import java.util.HashMap;
import java.util.Map;

@Service
public class UserTowerInferenceService {

    private OrtEnvironment environment;
    private OrtSession session;

    @PostConstruct
    public void init() throws OrtException, IOException {
        environment = OrtEnvironment.getEnvironment();
        ClassPathResource resource = new ClassPathResource("model/user_tower.onnx");
        if (!resource.exists()) {
            throw new IllegalStateException("model/user_tower.onnx not found. Run python-training/train_and_export.py first.");
        }
        try (InputStream inputStream = resource.getInputStream()) {
            session = environment.createSession(inputStream.readAllBytes(), new OrtSession.SessionOptions());
        }
    }

    public float[] inferUserEmbedding(FeatureEncoder.EncodedFeatures features) {
        try {
            long[] userArr = new long[]{features.getUserId()};

            try (OnnxTensor userTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(userArr), new long[]{1})) {
                Map<String, OnnxTensor> inputs = new HashMap<>();
                inputs.put("user_id", userTensor);

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
        if (session != null) session.close();
        if (environment != null) environment.close();
    }
}
