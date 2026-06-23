package com.recsys.application.retrieval;
import com.recsys.application.model.ScoredItems;
import com.recsys.application.experiment.ModelVariants;
import com.recsys.application.feature.FeatureEncoder;
import com.recsys.application.model.ModelArtifactLocator;
import com.recsys.application.model.Strings;

import ai.onnxruntime.*;

import java.nio.LongBuffer;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Set;
import com.recsys.domain.prediction.ScoredItem;

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
        this.modelFile = Strings.orDefault(modelFile, DEFAULT_MODEL_FILE);
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
        float[] scores = scoreBatch(features, new long[]{itemId});
        return scores[0];
    }

    private float[] scoreBatch(FeatureEncoder.EncodedFeatures features, long[] itemIds) {
        if (!ready || session == null) {
            throw new IllegalStateException("ONNX session is not initialized or has been closed");
        }
        if (features == null || itemIds == null || itemIds.length == 0) {
            return new float[0];
        }
        try {
            long[] userArr = new long[itemIds.length];
            for (int i = 0; i < userArr.length; i++) {
                userArr[i] = features.getUserId();
            }
            try (OnnxTensor userTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(userArr), new long[]{userArr.length});
                 OnnxTensor itemTensor = OnnxTensor.createTensor(environment, LongBuffer.wrap(itemIds), new long[]{itemIds.length})) {
                Map<String, OnnxTensor> inputs = Map.of("user_id", userTensor, "item_id", itemTensor);
                try (OrtSession.Result result = session.run(inputs)) {
                    return (float[]) result.get("score").get().getValue();
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

        List<String> itemIds = new ArrayList<>(candidateItemIds.size());
        long[] encodedItemIds = new long[candidateItemIds.size()];
        int count = 0;
        for (String itemId : candidateItemIds) {
            Long encodedItemId = featureEncoder.encodeItemId(itemId);
            if (encodedItemId == null) {
                continue;
            }
            itemIds.add(itemId);
            encodedItemIds[count++] = encodedItemId;
        }
        if (count == 0) {
            return List.of();
        }
        if (count < encodedItemIds.length) {
            long[] compact = new long[count];
            System.arraycopy(encodedItemIds, 0, compact, 0, count);
            encodedItemIds = compact;
        }

        float[] scores = scoreBatch(features, encodedItemIds);
        PriorityQueue<ScoredItem> best = ScoredItems.minHeap();
        int scoredCount = Math.min(scores.length, itemIds.size());
        for (int i = 0; i < scoredCount; i++) {
            double score = scores[i];
            ScoredItems.keepTopK(best, new ScoredItem(itemIds.get(i), score), k);
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
