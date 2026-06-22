package com.recsys.online.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.domain.RecommendationQuery;
import com.recsys.serving.BaseApiService;
import com.recsys.service.recommendation.RecommendationPipeline;

public final class OnlineRecommendV2Service extends BaseApiService {

    private final RecommendationPipeline pipeline;

    public OnlineRecommendV2Service(RecommendationPipeline pipeline) {
        this.pipeline = pipeline;
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
            try {
                RecommendationQuery query = readJsonBody(agg, RecommendationQuery.class);
                return writeJson(HttpStatus.OK, pipeline.recommend(query));
            } catch (BadRequestException | IllegalArgumentException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error in OnlineRecommendV2Service", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
