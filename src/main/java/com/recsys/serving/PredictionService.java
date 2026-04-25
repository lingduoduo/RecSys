package com.recsys.serving;

import com.recsys.features.PairPredictionService;
import com.recsys.models.PredictRequest;
import com.recsys.models.PredictResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;

public class PredictionService extends BaseApiServlet {

    private final PairPredictionService predictionService;

    public PredictionService(PairPredictionService predictionService) {
        this.predictionService = predictionService;
    }

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response) throws IOException {
        prepareJson(response);
        try {
            PredictRequest predictRequest = readJsonBody(request, PredictRequest.class);
            if (predictRequest.getInstances() == null || predictRequest.getInstances().isEmpty()) {
                writeError(response, HttpServletResponse.SC_BAD_REQUEST, "instances must not be empty");
                return;
            }

            writeJson(
                    response,
                    HttpServletResponse.SC_OK,
                    new PredictResponse(predictionService.predict(predictRequest.getInstances()))
            );
        } catch (BadRequestException | IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, e.getMessage());
        } catch (Exception e) {
            e.printStackTrace();
            writeError(response, HttpServletResponse.SC_INTERNAL_SERVER_ERROR, "internal server error");
        }
    }
}
