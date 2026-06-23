package com.recsys.domain.prediction;

import java.util.List;

public record PredictResponse(List<List<Double>> predictions) {
}
