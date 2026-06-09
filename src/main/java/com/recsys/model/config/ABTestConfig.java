package com.recsys.model.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "recsys.ab-test")
public class ABTestConfig {

    private boolean enabled = false;

    @Min(2)
    private int trafficSplitNumber = 5;

    @NotBlank
    private String bucketAVariant = "test";

    @NotBlank
    private String bucketBVariant = "training";

    @NotBlank
    private volatile String defaultVariant = "training";

    @NotBlank
    private String layerName = "default";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getTrafficSplitNumber() { return trafficSplitNumber; }
    public void setTrafficSplitNumber(int trafficSplitNumber) { this.trafficSplitNumber = trafficSplitNumber; }

    public String getBucketAVariant() { return bucketAVariant; }
    public void setBucketAVariant(String bucketAVariant) { this.bucketAVariant = bucketAVariant; }

    public String getBucketBVariant() { return bucketBVariant; }
    public void setBucketBVariant(String bucketBVariant) { this.bucketBVariant = bucketBVariant; }

    public String getDefaultVariant() { return defaultVariant; }
    public void setDefaultVariant(String defaultVariant) { this.defaultVariant = defaultVariant; }

    public String getLayerName() { return layerName; }
    public void setLayerName(String layerName) { this.layerName = layerName; }
}
