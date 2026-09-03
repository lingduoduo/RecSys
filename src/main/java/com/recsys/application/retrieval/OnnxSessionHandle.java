package com.recsys.application.retrieval;

import ai.onnxruntime.NodeInfo;
import ai.onnxruntime.OrtException;

import java.util.Map;

/**
 * The narrow slice of {@code OrtSession} that {@link UserTowerInferenceService} depends on.
 * Exists so contract validation can be exercised against a mock without generating malformed
 * ONNX binaries; production uses {@link OrtSessionHandle}.
 */
interface OnnxSessionHandle extends AutoCloseable {

    Map<String, NodeInfo> inputInfo() throws OrtException;

    Map<String, NodeInfo> outputInfo() throws OrtException;

    /**
     * Runs one batched two-tower invocation and returns the raw output tensor. Callers own
     * output validation — the adapter promises only to return whatever the model produced.
     */
    float[] run(String userInput, String itemInput, String outputName, long[] users, long[] items)
            throws OrtException;

    @Override
    void close();
}
