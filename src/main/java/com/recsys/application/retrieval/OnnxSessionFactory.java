package com.recsys.application.retrieval;

import ai.onnxruntime.OrtException;
import com.recsys.config.ModelServingProperties;

/** Opens an {@link OnnxSessionHandle} over verified model bytes with the configured execution settings. */
@FunctionalInterface
interface OnnxSessionFactory {

    OnnxSessionHandle open(byte[] modelBytes, ModelServingProperties.Onnx onnx) throws OrtException;
}
