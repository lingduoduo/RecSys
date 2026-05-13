package com.recsys.modelbased.model.service;

import com.recsys.modelbased.model.config.ABTestConfig;
import com.recsys.modelbased.model.dto.ModelVersionResponse;
import org.springframework.stereotype.Service;

import java.util.Comparator;

@Service
public class ModelVersionService {

    private final ModelRuntimeProvider runtimeProvider;
    private final ABTestConfig abTestConfig;
    private volatile String previousActiveVariant;

    public ModelVersionService(ModelRuntimeProvider runtimeProvider, ABTestConfig abTestConfig) {
        this.runtimeProvider = runtimeProvider;
        this.abTestConfig = abTestConfig;
    }

    public synchronized ModelVersionResponse listVersions() {
        String active = ModelVariants.require(abTestConfig.getDefaultVariant());
        var variants = runtimeProvider.loadedVariants().stream()
                .sorted(Comparator.comparing(ModelRuntimeProvider.LoadedVariant::variant))
                .map(v -> new ModelVersionResponse.VariantVersion(
                        v.variant(),
                        v.modelVersion(),
                        v.ready(),
                        v.variant().equals(active)))
                .toList();
        return new ModelVersionResponse(active, previousActiveVariant, variants);
    }

    public ModelVersionResponse preload(String variant) {
        runtimeProvider.getRuntime(ModelVariants.require(variant));
        return listVersions();
    }

    public synchronized ModelVersionResponse activate(String variant) {
        String normalized = ModelVariants.require(variant);
        ModelRuntime runtime = runtimeProvider.getRuntime(normalized);
        if (!runtime.isReady()) {
            throw new IllegalArgumentException("model variant is not ready: " + normalized);
        }

        String current = ModelVariants.require(abTestConfig.getDefaultVariant());
        if (!normalized.equals(current)) {
            previousActiveVariant = current;
            abTestConfig.setDefaultVariant(normalized);
        }
        return listVersions();
    }

    public synchronized ModelVersionResponse rollback() {
        if (previousActiveVariant == null || previousActiveVariant.isBlank()) {
            throw new IllegalArgumentException("no previous active model variant to roll back to");
        }

        String rollbackTarget = previousActiveVariant;
        String current = ModelVariants.require(abTestConfig.getDefaultVariant());
        runtimeProvider.getRuntime(rollbackTarget);
        abTestConfig.setDefaultVariant(rollbackTarget);
        previousActiveVariant = current;
        return listVersions();
    }
}
