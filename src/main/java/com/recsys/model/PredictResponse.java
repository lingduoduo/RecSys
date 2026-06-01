package com.recsys.model;

import java.util.List;

public record PredictResponse(List<List<Double>> predictions) {
}
