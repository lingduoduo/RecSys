package com.recsys.application.saga;
import com.recsys.application.saga.AwsStepFunctionsSagaDefinition;
import com.recsys.domain.saga.SagaStep;
import com.recsys.application.saga.SagaDefinition;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class AwsStepFunctionsSagaDefinitionTest {

    @Test
    void render_createsForwardTasksAndReverseCompensationChain() {
        SagaDefinition definition = new SagaDefinition("recommendation-refresh", List.of(
                SagaStep.awsTask(
                        "reserve-recommendation",
                        "arn:aws:lambda:us-east-1:123:function:reserveRecommendation",
                        "arn:aws:lambda:us-east-1:123:function:releaseRecommendation"
                ).withRetry(2, Duration.ofSeconds(1)),
                SagaStep.awsTask(
                        "publish-refresh-event",
                        "arn:aws:states:::events:putEvents",
                        ""
                )
        ));

        String json = AwsStepFunctionsSagaDefinition.render(definition);

        assertThat(json).contains("\"StartAt\": \"ReserveRecommendation\"");
        assertThat(json).contains("\"Resource\": \"arn:aws:lambda:us-east-1:123:function:reserveRecommendation\"");
        assertThat(json).contains("\"Next\": \"PublishRefreshEvent\"");
        assertThat(json).contains("\"Next\": \"CompensateReserveRecommendation\"");
        assertThat(json).contains("\"CompensateReserveRecommendation\"");
        assertThat(json).contains("\"Resource\": \"arn:aws:lambda:us-east-1:123:function:releaseRecommendation\"");
        assertThat(json).contains("\"Next\": \"SagaFailed\"");
    }
}
