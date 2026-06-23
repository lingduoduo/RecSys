package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.application.model.PairPredictionService;
import com.recsys.domain.prediction.PredictRequest;
import com.recsys.domain.prediction.PredictResponse;

public class PredictionService extends BaseApiService {

    private final PairPredictionService predictionService;

    public PredictionService(PairPredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
        return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
            try {
                PredictRequest pr = readJsonBody(agg, PredictRequest.class);
                if (pr.getInstances() == null || pr.getInstances().isEmpty())
                    return writeError(HttpStatus.BAD_REQUEST, "instances must not be empty");
                return writeJson(HttpStatus.OK, new PredictResponse(predictionService.predict(pr.getInstances())));
            } catch (BadRequestException | IllegalArgumentException e) {
                return writeError(HttpStatus.BAD_REQUEST, e.getMessage());
            } catch (Exception e) {
                log.error("Unexpected error in PredictionService", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }
}
