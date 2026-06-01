package com.recsys.infrastructure.autoscaling;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record NetworkConfig(
        String vpcId,
        List<String> availabilityZones,
        List<String> subnetIds
) {
    public NetworkConfig {
        Objects.requireNonNull(vpcId, "vpcId");
        Objects.requireNonNull(availabilityZones, "availabilityZones");
        Objects.requireNonNull(subnetIds, "subnetIds");
        if (vpcId.isBlank()) throw new IllegalArgumentException("vpcId must not be blank");
        if (availabilityZones.isEmpty()) {
            throw new IllegalArgumentException("at least one availability zone is required");
        }
        availabilityZones = List.copyOf(availabilityZones);
        subnetIds = List.copyOf(subnetIds);
    }

    public static Builder builder(String vpcId) {
        return new Builder(vpcId);
    }

    public static final class Builder {
        private final String vpcId;
        private final List<String> availabilityZones = new ArrayList<>();
        private final List<String> subnetIds = new ArrayList<>();

        private Builder(String vpcId) {
            this.vpcId = Objects.requireNonNull(vpcId, "vpcId");
        }

        public Builder availabilityZone(String az) {
            availabilityZones.add(Objects.requireNonNull(az, "az"));
            return this;
        }

        public Builder subnet(String subnetId) {
            subnetIds.add(Objects.requireNonNull(subnetId, "subnetId"));
            return this;
        }

        public NetworkConfig build() {
            return new NetworkConfig(vpcId, availabilityZones, subnetIds);
        }
    }
}
