package com.recsys.models;

import java.util.List;

public record PredictResponse(List<List<Double>> predictions) {
}
