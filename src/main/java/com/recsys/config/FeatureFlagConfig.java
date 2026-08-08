package com.recsys.config;

import com.recsys.infrastructure.featureflags.FeatureFlagProvider;
import com.recsys.infrastructure.featureflags.FeatureFlagService;
import com.recsys.infrastructure.featureflags.providers.CachingFeatureFlagProvider;
import com.recsys.infrastructure.featureflags.providers.CompositeFeatureFlagProvider;
import com.recsys.infrastructure.featureflags.providers.EnvFeatureFlagProvider;
import com.recsys.infrastructure.featureflags.providers.PostHogFeatureFlagProvider;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(FeatureFlagConfig.Properties.class)
public class FeatureFlagConfig {

    @Bean
    public FeatureFlagProvider featureFlagProvider(Properties properties) {
        List<FeatureFlagProvider> providers = new ArrayList<>();
        providers.add(new EnvFeatureFlagProvider(properties.environmentPrefix));
        PostHog postHog = properties.getPostHog();
        if (postHog.isEnabled() && postHog.getApiKey() != null && !postHog.getApiKey().isBlank()) {
            FeatureFlagProvider raw = new PostHogFeatureFlagProvider(
                    postHog.getApiKey(), postHog.getDistinctIdSalt(),
                    postHog.getHost(), postHog.getTimeout());
            providers.add(new CachingFeatureFlagProvider(raw, postHog.getCacheTtl()));
        }
        return new CompositeFeatureFlagProvider(providers);
    }

    @Bean
    public FeatureFlagService featureFlagService(FeatureFlagProvider featureFlagProvider) {
        return new FeatureFlagService(featureFlagProvider);
    }

    @ConfigurationProperties(prefix = "recsys.feature-flags")
    public static class Properties {
        private String environmentPrefix = "FEATURE_FLAG_";
        private final PostHog postHog = new PostHog();

        public String getEnvironmentPrefix() { return environmentPrefix; }
        public void setEnvironmentPrefix(String environmentPrefix) { this.environmentPrefix = environmentPrefix; }
        public PostHog getPostHog() { return postHog; }
    }

    public static class PostHog {
        private boolean enabled;
        private String apiKey;
        private String distinctIdSalt;
        private URI host = URI.create("https://us.i.posthog.com");
        private Duration timeout = Duration.ofSeconds(2);
        private Duration cacheTtl = Duration.ofMinutes(1);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public String getDistinctIdSalt() { return distinctIdSalt; }
        public void setDistinctIdSalt(String distinctIdSalt) { this.distinctIdSalt = distinctIdSalt; }
        public URI getHost() { return host; }
        public void setHost(URI host) { this.host = host; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public Duration getCacheTtl() { return cacheTtl; }
        public void setCacheTtl(Duration cacheTtl) {
            if (cacheTtl == null || cacheTtl.isNegative() || cacheTtl.isZero()) {
                throw new IllegalArgumentException(
                        "recsys.feature-flags.post-hog.cache-ttl must be a positive duration, got: " + cacheTtl);
            }
            this.cacheTtl = cacheTtl;
        }
    }
}
