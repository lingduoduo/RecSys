package com.recsys.infrastructure.autoscaling;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public record LaunchTemplate(
        String templateId,
        String imageId,
        String instanceType,
        int servicePort,
        InstancePurchasingOption purchasingOption,
        List<String> securityGroupIds,
        String keyPairName,
        String userData
) {
    public LaunchTemplate {
        Objects.requireNonNull(templateId, "templateId");
        Objects.requireNonNull(imageId, "imageId");
        Objects.requireNonNull(instanceType, "instanceType");
        Objects.requireNonNull(purchasingOption, "purchasingOption");
        Objects.requireNonNull(securityGroupIds, "securityGroupIds");
        if (templateId.isBlank()) throw new IllegalArgumentException("templateId must not be blank");
        if (imageId.isBlank())    throw new IllegalArgumentException("imageId must not be blank");
        if (instanceType.isBlank()) throw new IllegalArgumentException("instanceType must not be blank");
        if (servicePort < 1 || servicePort > 65535) {
            throw new IllegalArgumentException("servicePort out of range: " + servicePort);
        }
        securityGroupIds = List.copyOf(securityGroupIds);
        userData = userData == null ? "" : userData;
        keyPairName = keyPairName == null ? "" : keyPairName;
    }

    public static Builder builder(String templateId) {
        return new Builder(templateId);
    }

    public static final class Builder {
        private final String templateId;
        private String imageId;
        private String instanceType = "t3.medium";
        private int servicePort = 80;
        private InstancePurchasingOption purchasingOption = InstancePurchasingOption.ON_DEMAND;
        private final List<String> securityGroupIds = new ArrayList<>();
        private String keyPairName;
        private String userData;

        private Builder(String templateId) {
            this.templateId = Objects.requireNonNull(templateId, "templateId");
        }

        public Builder imageId(String imageId) {
            this.imageId = Objects.requireNonNull(imageId, "imageId");
            return this;
        }

        public Builder instanceType(String instanceType) {
            this.instanceType = Objects.requireNonNull(instanceType, "instanceType");
            return this;
        }

        public Builder servicePort(int servicePort) {
            this.servicePort = servicePort;
            return this;
        }

        public Builder purchasingOption(InstancePurchasingOption option) {
            this.purchasingOption = Objects.requireNonNull(option, "purchasingOption");
            return this;
        }

        public Builder securityGroup(String sgId) {
            securityGroupIds.add(Objects.requireNonNull(sgId, "sgId"));
            return this;
        }

        public Builder keyPair(String keyPairName) {
            this.keyPairName = keyPairName;
            return this;
        }

        public Builder userData(String userData) {
            this.userData = userData;
            return this;
        }

        public LaunchTemplate build() {
            if (imageId == null) throw new IllegalStateException("imageId is required");
            return new LaunchTemplate(templateId, imageId, instanceType, servicePort,
                    purchasingOption, securityGroupIds, keyPairName, userData);
        }
    }
}
