package com.recsys.config;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Runtime tuning for ONNX inference and parallel recommendation recall.
 */
@Validated
@ConfigurationProperties(prefix = "recsys.model")
public class ModelServingProperties {

    @Valid
    private final Onnx onnx = new Onnx();

    @Valid
    private final Recall recall = new Recall();

    public Onnx getOnnx() {
        return onnx;
    }

    public Recall getRecall() {
        return recall;
    }

    public enum ExecutionMode {
        SEQUENTIAL,
        PARALLEL
    }

    public static class Onnx {

        @Positive
        private int intraOpThreads = 1;

        @Positive
        private int interOpThreads = 1;

        @NotNull
        private ExecutionMode executionMode = ExecutionMode.SEQUENTIAL;

        public int getIntraOpThreads() {
            return intraOpThreads;
        }

        public void setIntraOpThreads(int intraOpThreads) {
            this.intraOpThreads = requirePositive(intraOpThreads, "intraOpThreads");
        }

        public int getInterOpThreads() {
            return interOpThreads;
        }

        public void setInterOpThreads(int interOpThreads) {
            this.interOpThreads = requirePositive(interOpThreads, "interOpThreads");
        }

        public ExecutionMode getExecutionMode() {
            return executionMode;
        }

        public void setExecutionMode(ExecutionMode executionMode) {
            if (executionMode == null) {
                throw new IllegalArgumentException("executionMode must not be null");
            }
            this.executionMode = executionMode;
        }
    }

    public static class Recall {

        @Min(0)
        private int coreThreads = defaultCoreThreads();

        @Positive
        private int queueCapacity = 256;

        @Positive
        private long timeoutMs = 200;

        public int getCoreThreads() {
            return coreThreads;
        }

        public void setCoreThreads(int coreThreads) {
            if (coreThreads < 0) {
                throw new IllegalArgumentException("coreThreads must not be negative");
            }
            this.coreThreads = coreThreads == 0 ? defaultCoreThreads() : coreThreads;
        }

        public int getQueueCapacity() {
            return queueCapacity;
        }

        public void setQueueCapacity(int queueCapacity) {
            this.queueCapacity = requirePositive(queueCapacity, "queueCapacity");
        }

        public long getTimeoutMs() {
            return timeoutMs;
        }

        public void setTimeoutMs(long timeoutMs) {
            this.timeoutMs = requirePositive(timeoutMs, "timeoutMs");
        }
    }

    private static int defaultCoreThreads() {
        return Math.max(1, Runtime.getRuntime().availableProcessors() * 2);
    }

    private static int requirePositive(int value, String propertyName) {
        if (value < 1) {
            throw new IllegalArgumentException(propertyName + " must be at least 1");
        }
        return value;
    }

    private static long requirePositive(long value, String propertyName) {
        if (value < 1) {
            throw new IllegalArgumentException(propertyName + " must be at least 1");
        }
        return value;
    }
}
