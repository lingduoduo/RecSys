package com.recsys.model;

import java.util.ArrayList;
import java.util.List;

public class PredictRequest {
    private List<PredictInstance> instances = new ArrayList<>();

    public PredictRequest() {
    }

    public List<PredictInstance> getInstances() {
        return instances;
    }

    public void setInstances(List<PredictInstance> instances) {
        this.instances = instances == null ? new ArrayList<>() : instances;
    }
}
