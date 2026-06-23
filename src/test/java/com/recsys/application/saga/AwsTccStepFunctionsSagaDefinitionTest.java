package com.recsys.application.saga;
import com.recsys.application.saga.AwsTccStepFunctionsSagaDefinition;
import com.recsys.domain.saga.SagaStep;
import com.recsys.application.saga.SagaDefinition;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AwsTccStepFunctionsSagaDefinitionTest {

    @Test
    void render_createsTryConfirmAndCancelPhases() {
        SagaDefinition definition = new SagaDefinition("recommendation-refresh-tcc", List.of(
                SagaStep.tccAwsTask(
                        "reserve-candidate-set",
                        "arn:aws:lambda:us-east-1:123:function:tryCandidateSet",
                        "arn:aws:lambda:us-east-1:123:function:confirmCandidateSet",
                        "arn:aws:lambda:us-east-1:123:function:cancelCandidateSet"
                ).withRetry(2, Duration.ofSeconds(1)),
                SagaStep.tccAwsTask(
                        "reserve-feature-refresh",
                        "arn:aws:lambda:us-east-1:123:function:tryFeatureRefresh",
                        "arn:aws:lambda:us-east-1:123:function:confirmFeatureRefresh",
                        "arn:aws:lambda:us-east-1:123:function:cancelFeatureRefresh"
                )
        ));

        String json = AwsTccStepFunctionsSagaDefinition.render(definition);

        assertThat(json).contains("\"StartAt\": \"TryReserveCandidateSet\"");
        assertThat(json).contains("\"TryReserveCandidateSet\"");
        assertThat(json).contains("\"ConfirmReserveCandidateSet\"");
        assertThat(json).contains("\"CancelReserveCandidateSet\"");
        assertThat(json).contains("\"Resource\": \"arn:aws:lambda:us-east-1:123:function:tryCandidateSet\"");
        assertThat(json).contains("\"Resource\": \"arn:aws:lambda:us-east-1:123:function:confirmCandidateSet\"");
        assertThat(json).contains("\"Resource\": \"arn:aws:lambda:us-east-1:123:function:cancelCandidateSet\"");
        assertThat(json).contains("\"Next\": \"ConfirmReserveCandidateSet\"");
        assertThat(json).contains("\"Next\": \"CancelReserveCandidateSet\"");
        assertThat(json).contains("\"ManualReconciliationRequired\"");
    }
}
