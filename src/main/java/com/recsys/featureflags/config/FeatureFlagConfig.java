package com.recsys.featureflags.config;

import com.recsys.featureflags.FeatureFlagProvider;
import com.recsys.featureflags.FeatureFlagService;
import com.recsys.featureflags.providers.CachingFeatureFlagProvider;
import com.recsys.featureflags.providers.CompositeFeatureFlagProvider;
import com.recsys.featureflags.providers.EnvFeatureFlagProvider;
import com.recsys.featureflags.providers.PostHogFeatureFlagProvider;
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
        PostHog postHog = properties.postHog;
        if (postHog.enabled && postHog.apiKey != null && !postHog.apiKey.isBlank()) {
            FeatureFlagProvider raw = new PostHogFeatureFlagProvider(
                    postHog.apiKey, postHog.host, postHog.timeout);
            providers.add(new CachingFeatureFlagProvider(raw, postHog.cacheTtl));
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
        private URI host = URI.create("https://us.i.posthog.com");
        private Duration timeout = Duration.ofSeconds(2);
        private Duration cacheTtl = Duration.ofMinutes(1);

        public boolean isEnabled() { return enabled; }
        public void setEnabled(boolean enabled) { this.enabled = enabled; }
        public String getApiKey() { return apiKey; }
        public void setApiKey(String apiKey) { this.apiKey = apiKey; }
        public URI getHost() { return host; }
        public void setHost(URI host) { this.host = host; }
        public Duration getTimeout() { return timeout; }
        public void setTimeout(Duration timeout) { this.timeout = timeout; }
        public Duration getCacheTtl() { return cacheTtl; }
        public void setCacheTtl(Duration cacheTtl) { this.cacheTtl = cacheTtl; }
    }
}
