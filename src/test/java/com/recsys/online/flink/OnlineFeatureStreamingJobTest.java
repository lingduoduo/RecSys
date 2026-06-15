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
}
