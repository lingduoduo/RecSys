package com.recsys.domain;

import java.util.List;

public record PredictResponse(List<List<Double>> predictions) {
}
