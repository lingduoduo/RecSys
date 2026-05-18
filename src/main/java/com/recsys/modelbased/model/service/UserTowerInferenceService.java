package com.recsys.modelbased.model.service;

import ai.onnxruntime.*;

import java.nio.LongBuffer;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import com.recsys.modelbased.model.dto.ScoredItem;

public class UserTowerInferenceService {

    private static final String DEFAULT_MODEL_FILE = "dssm_model.onnx";

    private final ModelArtifactLocator artifactLocator;
    private final String variant;
    private final String modelFile;
    private OrtEnvironment environment;
    private OrtSession session;
    private volatile boolean ready = false;

    public UserTowerInferenceService(ModelArtifactLocator artifactLocator, String variant) {
        this(artifactLocator, variant, DEFAULT_MODEL_FILE);
    }

    public UserTowerInferenceService(ModelArtifactLocator artifactLocator, String variant, String modelFile) {
        this.artifactLocator = artifactLocator;
        this.variant = ModelVariants.trimOrEmpty(variant);
        this.modelFile = modelFile == null || modelFile.isBlank() ? DEFAULT_MODEL_FILE : modelFile.trim();
    }

    public void init() throws Exception {
        environment = OrtEnvironment.getEnvironment();
        try {
            session = environment.createSession(
                    artifactLocator.readModelBytes(variant, modelFile),
                    new OrtSession.SessionOptions()
            );
            ready = true;
        } catch (IllegalStateException e) {
            throw new IllegalStateException(modelFile + " not found at "
                    + artifactLocator.describeModelLocation(variant, modelFile)
                    + ". Set recsys.model.artifacts-dir to an external model directory, or place artifacts under classpath:artifacts/model/<variant>/.", e);
        }
    }

    public boolean isReady() {
        return ready;
    }

    public double score(FeatureEncoder.EncodedFeatures features, long itemId) {
        if (!ready || session == null) {
            throw new IllegalStateException("ONNX session is not initialized or has been closed");
        }
        try {
            long[] userArr = new long[]{features.getUserId()};
            long[] itemArr = new long[]{itemId};
            try (OnnxTensor userTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(userArr), new long[]{1});
                 OnnxTensor itemTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(itemArr), new long[]{1})) {
                Map<String, OnnxTensor> inputs = Map.of("user_id", userTensor, "item_id", itemTensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    float[] output = (float[]) result.get("score").get().getValue();
                    return output[0];
                }
            }
        } catch (OrtException e) {
            throw new RuntimeException("Failed to run ONNX inference", e);
        }
    }

    public List<ScoredItem> scoreCandidates(
            FeatureEncoder.EncodedFeatures features,
            FeatureEncoder featureEncoder,
            Set<String> candidateItemIds,
            int k
    ) {
        if (candidateItemIds == null || candidateItemIds.isEmpty() || k <= 0) {
            return List.of();
        }

        Set<String> seen = new HashSet<>();
        PriorityQueue<ScoredItem> best = ScoredItems.minHeap();
        for (String itemId : candidateItemIds) {
            if (!seen.add(itemId)) continue;
            Long encodedItemId = featureEncoder.encodeItemId(itemId);
            if (encodedItemId == null) continue;

            double score = score(features, encodedItemId);
            ScoredItems.keepTopK(best, new ScoredItem(itemId, score), k);
        }

        return ScoredItems.descending(best);
    }

    public void close() throws OrtException {
        // OrtEnvironment is a JVM-wide singleton managed by the ONNX Runtime native layer.
        // Closing it here would invalidate every other OrtSession in the process (e.g. other
        // A/B-test variants loaded by ModelRuntimeProvider). Only close the session.
        if (session != null) session.close();
    }
}
