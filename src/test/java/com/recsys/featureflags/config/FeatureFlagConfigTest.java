package com.recsys.featureflags.config;

import com.recsys.featureflags.FeatureFlagProvider;
import com.recsys.featureflags.FeatureFlagService;
import com.recsys.featureflags.models.FeatureFlag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class FeatureFlagConfigTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withUserConfiguration(FeatureFlagConfig.class);

    @Test
    void createsFeatureFlagBeansWithDefaults() {
        contextRunner.run(context -> {
            assertThat(context).hasSingleBean(FeatureFlagProvider.class);
            assertThat(context).hasSingleBean(FeatureFlagService.class);

            FeatureFlagService service = context.getBean(FeatureFlagService.class);
            assertThat(service.isEnabled(FeatureFlag.enabledByDefault("unknown-flag"))).isTrue();
        });
    }

    @Test
    void bindsConfigurationProperties() {
        contextRunner
                .withPropertyValues(
                        "recsys.feature-flags.environment-prefix=FF_",
                        "recsys.feature-flags.post-hog.enabled=false",
                        "recsys.feature-flags.post-hog.host=https://eu.i.posthog.com",
                        "recsys.feature-flags.post-hog.timeout=500ms")
                .run(context -> {
                    FeatureFlagConfig.Properties config = context.getBean(FeatureFlagConfig.Properties.class);

                    assertThat(config.getEnvironmentPrefix()).isEqualTo("FF_");
                    assertThat(config.getPostHog().getHost()).hasToString("https://eu.i.posthog.com");
                    assertThat(config.getPostHog().getTimeout()).isEqualTo(java.time.Duration.ofMillis(500));
                    assertThat(config.getPostHog().getCacheTtl()).isEqualTo(java.time.Duration.ofMinutes(1));
                });
    }

    @Test
    void posthogProviderIsWrappedInCachingProviderWhenEnabled() {
        contextRunner
                .withPropertyValues(
                        "recsys.feature-flags.post-hog.enabled=true",
                        "recsys.feature-flags.post-hog.api-key=phc_test")
                .run(context -> {
                    FeatureFlagProvider provider = context.getBean(FeatureFlagProvider.class);
                    assertThat(provider).isInstanceOf(com.recsys.featureflags.providers.CompositeFeatureFlagProvider.class);
                });
    }

    @Test
    void bindsPostHogCacheTtl() {
        contextRunner
                .withPropertyValues(
                        "recsys.feature-flags.post-hog.enabled=true",
                        "recsys.feature-flags.post-hog.api-key=phc_test",
                        "recsys.feature-flags.post-hog.cache-ttl=30s")
                .run(context -> {
                    FeatureFlagConfig.Properties config = context.getBean(FeatureFlagConfig.Properties.class);
                    assertThat(config.getPostHog().getCacheTtl())
                            .isEqualTo(java.time.Duration.ofSeconds(30));
                });
    }
}
