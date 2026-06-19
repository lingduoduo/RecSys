package com.recsys.config;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

@Validated
@ConfigurationProperties(prefix = "recsys.ab-test")
public class ABTestConfig {

    private boolean enabled = false;

    @Min(0)
    private int bucketAPercent = 20;

    @Min(0)
    private int bucketBPercent = 20;

    @NotBlank
    private String bucketAVariant = "test";

    @NotBlank
    private String bucketBVariant = "training";

    @NotBlank
    private String defaultVariant = "training";

    @NotBlank
    private String layerName = "default";

    public boolean isEnabled() { return enabled; }
    public void setEnabled(boolean enabled) { this.enabled = enabled; }

    public int getBucketAPercent() { return bucketAPercent; }
    public void setBucketAPercent(int bucketAPercent) { this.bucketAPercent = bucketAPercent; }

    public int getBucketBPercent() { return bucketBPercent; }
    public void setBucketBPercent(int bucketBPercent) { this.bucketBPercent = bucketBPercent; }

    public String getBucketAVariant() { return bucketAVariant; }
    public void setBucketAVariant(String bucketAVariant) { this.bucketAVariant = bucketAVariant; }

    public String getBucketBVariant() { return bucketBVariant; }
    public void setBucketBVariant(String bucketBVariant) { this.bucketBVariant = bucketBVariant; }

    public String getDefaultVariant() { return defaultVariant; }
    public void setDefaultVariant(String defaultVariant) { this.defaultVariant = defaultVariant; }

    public String getLayerName() { return layerName; }
    public void setLayerName(String layerName) { this.layerName = layerName; }

    @jakarta.validation.constraints.AssertTrue(message = "bucketAPercent + bucketBPercent must be <= 100")
    public boolean isAllocationWithinBounds() {
        return bucketAPercent + bucketBPercent <= 100;
    }
}
