package com.recsys.application.model;
import com.recsys.application.model.ModelArtifactLocator;
import com.recsys.application.model.ModelArtifactService;

import com.recsys.infrastructure.redis.RedisEmbeddingStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class ModelArtifactServiceRedisTest {

    @TempDir
    Path tempDir;

    @Test
    void itemEmbeddings_canLoadFromRedisStore() throws IOException {
        writeFeatureConfig();
        ModelArtifactService redisBacked = new ModelArtifactService(
                new ModelArtifactLocator(tempDir.toString(), ""),
                "training",
                new StubRedisEmbeddingStore(Map.of(
                        1, vector(16, 0.1f),
                        2, vector(16, 0.2f)
                )));

        redisBacked.loadArtifacts();

        assertThat(redisBacked.getModelVersion()).isEqualTo("demo-model-ratings-v1");
        assertThat(redisBacked.getItemEmbeddings()).containsOnlyKeys("1", "2");
        assertThat(redisBacked.getItemEmbeddings().get("1")).hasSize(16);
    }

    @Test
    void redisItemEmbeddings_validateDimension() throws IOException {
        writeFeatureConfig();
        ModelArtifactService redisBacked = new ModelArtifactService(
                new ModelArtifactLocator(tempDir.toString(), ""),
                "training",
                new StubRedisEmbeddingStore(Map.of(1, vector(8, 0.1f))));

        org.assertj.core.api.Assertions.assertThatThrownBy(redisBacked::loadArtifacts)
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("dimension mismatch");
    }

    private void writeFeatureConfig() throws IOException {
        Path variantDir = tempDir.resolve("training");
        Files.createDirectories(variantDir);
        Files.write(variantDir.resolve("dssm_model.onnx"),
                new ModelArtifactLocator("", "").readModelBytes("dssm_model.onnx"));
        Files.writeString(variantDir.resolve("feature_config.json"), """
                {
                  "model_version": "demo-model-ratings-v1",
                  "embedding_dim": 16,
                  "user_vocab": { "__UNK__": 0, "123": 1 },
                  "item_vocab": { "1": 0, "2": 1 }
                }
                """);
    }

    private static float[] vector(int size, float value) {
        float[] vec = new float[size];
        java.util.Arrays.fill(vec, value);
        return vec;
    }

    private static final class StubRedisEmbeddingStore extends RedisEmbeddingStore {
        private final Map<Integer, float[]> embeddings;

        private StubRedisEmbeddingStore(Map<Integer, float[]> embeddings) {
            super(null, "i2vEmb");
            this.embeddings = embeddings;
        }

        @Override
        public Map<Integer, float[]> loadAll() {
            return embeddings;
        }
    }
}
