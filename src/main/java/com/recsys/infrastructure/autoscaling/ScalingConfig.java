package com.recsys.infrastructure.autoscaling;

public record ScalingConfig(int minSize, int maxSize, int desiredCapacity) {

    public ScalingConfig {
        if (minSize < 0) throw new IllegalArgumentException("minSize must be >= 0");
        if (maxSize < 1) throw new IllegalArgumentException("maxSize must be >= 1");
        if (minSize > maxSize) {
            throw new IllegalArgumentException("minSize (" + minSize + ") cannot exceed maxSize (" + maxSize + ")");
        }
        if (desiredCapacity < minSize || desiredCapacity > maxSize) {
            throw new IllegalArgumentException(
                    "desiredCapacity (" + desiredCapacity + ") must be between minSize and maxSize");
        }
    }

    public int clamp(int desired) {
        return Math.max(minSize, Math.min(maxSize, desired));
    }

    public static ScalingConfig of(int minSize, int maxSize, int desiredCapacity) {
        return new ScalingConfig(minSize, maxSize, desiredCapacity);
    }
}
