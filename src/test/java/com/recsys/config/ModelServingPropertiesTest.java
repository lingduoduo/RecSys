package com.recsys.config;

import org.junit.jupiter.api.Test;

import static com.recsys.config.ModelServingProperties.ExecutionMode.SEQUENTIAL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ModelServingPropertiesTest {

    @Test
    void defaultsAreConservative() {
        ModelServingProperties p = new ModelServingProperties();

        assertThat(p.getOnnx().getIntraOpThreads()).isEqualTo(1);
        assertThat(p.getOnnx().getInterOpThreads()).isEqualTo(1);
        assertThat(p.getOnnx().getExecutionMode()).isEqualTo(SEQUENTIAL);
        assertThat(p.getRecall().getQueueCapacity()).isEqualTo(256);
        assertThat(p.getRecall().getTimeoutMs()).isEqualTo(200);
    }

    @Test
    void rejectsNonPositiveThreadAndQueueValues() {
        ModelServingProperties p = new ModelServingProperties();

        assertThatThrownBy(() -> p.getOnnx().setIntraOpThreads(0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> p.getRecall().setQueueCapacity(0))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
