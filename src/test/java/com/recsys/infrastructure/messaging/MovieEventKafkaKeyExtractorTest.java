package com.recsys.infrastructure.messaging;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class MovieEventKafkaKeyExtractorTest {

    @Test
    void extract_acceptsNumericUserId() {
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"userId\":42}"))
                .contains("42");
    }

    @Test
    void extract_acceptsFinalPositiveIntegerSuffix() {
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"user_id\":\"user_42\"}"))
                .contains("42");
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"user_id\":\"00042\"}"))
                .contains("42");
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"userId\":1e3}"))
                .contains("1000");
    }

    @Test
    void extract_returnsEmptyForMissingUserId() {
        assertThat(MovieEventKafkaKeyExtractor.extract("{}"))
                .isEmpty();
    }

    @Test
    void extract_returnsEmptyForMalformedJson() {
        assertThat(MovieEventKafkaKeyExtractor.extract("not-json"))
                .isEmpty();
    }

    @Test
    void extract_rejectsNonPositiveAndOverflowingIds() {
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"userId\":0}")).isEmpty();
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"userId\":-1}")).isEmpty();
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"userId\":9223372036854775808}")).isEmpty();
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"userId\":2147483648}")).isEmpty();
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"userId\":9007199254740992}")).isEmpty();
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"userId\":1.5}")).isEmpty();
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"user_id\":\"1e3\"}")).isEmpty();
        assertThat(MovieEventKafkaKeyExtractor.extract("{\"user_id\":\"user_not-a-number\"}")).isEmpty();
    }
}
