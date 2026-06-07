package com.recsys.featureflags.providers;

import com.recsys.featureflags.FeatureFlagProvider;
import com.recsys.featureflags.models.FeatureFlag;

import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

public class EnvFeatureFlagProvider implements FeatureFlagProvider {

    private final String prefix;
    private final Function<String, String> environment;

    public EnvFeatureFlagProvider(String prefix) {
        this(prefix, System::getenv);
    }

    public EnvFeatureFlagProvider(String prefix, Function<String, String> environment) {
        this.prefix = prefix == null ? "" : prefix;
        this.environment = environment;
    }

    @Override
    public Optional<Boolean> resolve(FeatureFlag flag, String distinctId, Map<String, Object> properties) {
        String raw = environment.apply(toEnvironmentName(flag.key()));
        if (raw == null || raw.isBlank()) {
            return Optional.empty();
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "true", "1", "yes", "on", "enabled" -> Optional.of(true);
            case "false", "0", "no", "off", "disabled" -> Optional.of(false);
            default -> Optional.empty();
        };
    }

    public String toEnvironmentName(String flagKey) {
        String normalized = flagKey
                .replaceAll("([a-z0-9])([A-Z])", "$1_$2")
                .replaceAll("[^A-Za-z0-9]+", "_")
                .replaceAll("^_+|_+$", "")
                .toUpperCase(Locale.ROOT);
        return prefix + normalized;
    }
}
