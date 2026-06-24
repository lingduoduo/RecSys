package com.recsys.api.rest;

import com.recsys.config.FeatureFlagConfig;
import com.recsys.config.ABTestConfig;
import com.recsys.config.HealthProperties;
import com.recsys.config.RecommendationCacheProperties;
import com.recsys.config.LoginProperties;
import com.recsys.config.RedisProperties;
import com.recsys.config.SubmitTokenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication(scanBasePackages = {"com.recsys.api", "com.recsys.config", "com.recsys.exception",
        "com.recsys.metrics", "com.recsys.jvm", "com.recsys.tracing", "com.recsys.reliability", "com.recsys.application"})
@Import(FeatureFlagConfig.class)
@EnableConfigurationProperties({
        HealthProperties.class,
        ABTestConfig.class,
        RecommendationCacheProperties.class,
        SubmitTokenProperties.class,
        LoginProperties.class,
        RedisProperties.class
})
public class ModelApplication {
    public static void main(String[] args) {
        SpringApplication.run(ModelApplication.class, args);
    }
}
