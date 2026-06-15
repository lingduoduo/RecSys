package com.recsys.online.flink;

import com.recsys.infrastructure.vectordb.VectorMath;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.assertThat;

class OnlineFeatureStreamingJobTest {

    @Test
    void encodeVectorUsesSpaceSeparator() throws Exception {
        var method = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("encodeVector", double[].class);
        method.setAccessible(true);
        var fn = new OnlineFeatureStreamingJob.UserEmbeddingFunction(4, 3600);
        String encoded = (String) method.invoke(fn, new double[]{1.0, 0.0, 0.0, 0.0});
        assertThat(encoded).doesNotContain(",");
        assertThat(encoded).contains(" ");
    }

    @Test
    void encodedVectorIsParsableByVectorMath() throws Exception {
        var method = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("encodeVector", double[].class);
        method.setAccessible(true);
        var fn = new OnlineFeatureStreamingJob.UserEmbeddingFunction(4, 3600);
        String encoded = (String) method.invoke(fn, new double[]{3.0, 4.0, 0.0, 0.0});
        float[] parsed = VectorMath.parseVector(encoded);
        assertThat(parsed).hasSize(4);
        assertThat(parsed[0]).isCloseTo(0.6f, org.assertj.core.data.Offset.offset(0.001f));
        assertThat(parsed[1]).isCloseTo(0.8f, org.assertj.core.data.Offset.offset(0.001f));
    }

    @Test
    void accumulatesRawCountsNotNormalisedValues() throws Exception {
        // encodeRaw must produce parseable space-separated values
        var rawMethod = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("encodeRaw", double[].class);
        rawMethod.setAccessible(true);
        var encodeMethod = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("encodeVector", double[].class);
        encodeMethod.setAccessible(true);
        var fn = new OnlineFeatureStreamingJob.UserEmbeddingFunction(4, 3600);

        // Simulate: after first event bucket 0 gets weight 2 (click)
        double[] rawAfterFirst = {2.0, 0.0, 0.0, 0.0};
        String rawStored = (String) rawMethod.invoke(fn, rawAfterFirst);

        // Simulate state restore: parse the raw stored value and accumulate a second event
        var parseMethod = OnlineFeatureStreamingJob.UserEmbeddingFunction.class
                .getDeclaredMethod("parseVector", String.class, int.class);
        parseMethod.setAccessible(true);
        double[] restored = (double[]) parseMethod.invoke(fn, rawStored, 4);

        // Add another click to bucket 1
        restored[1] += 2.0;

        // The raw vector should reflect both events, not corrupted normalised values
        assertThat(restored[0]).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.001));
        assertThat(restored[1]).isCloseTo(2.0, org.assertj.core.data.Offset.offset(0.001));

        // Redis output should be normalised (both buckets equal → both ~0.707)
        String redisOutput = (String) encodeMethod.invoke(fn, restored);
        float[] parsed = VectorMath.parseVector(redisOutput);
        assertThat(parsed[0]).isCloseTo(0.707f, org.assertj.core.data.Offset.offset(0.01f));
        assertThat(parsed[1]).isCloseTo(0.707f, org.assertj.core.data.Offset.offset(0.01f));
    }
}
