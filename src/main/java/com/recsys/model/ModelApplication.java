package com.recsys.model;

import com.recsys.featureflags.config.FeatureFlagConfig;
import com.recsys.config.ABTestConfig;
import com.recsys.config.HealthProperties;
import com.recsys.config.RecommendationCacheProperties;
import com.recsys.config.LoginProperties;
import com.recsys.config.SubmitTokenProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Import;

@SpringBootApplication
@Import(FeatureFlagConfig.class)
@EnableConfigurationProperties({
        HealthProperties.class,
        ABTestConfig.class,
        RecommendationCacheProperties.class,
        SubmitTokenProperties.class,
        LoginProperties.class
})
public class ModelApplication {
    public static void main(String[] args) {
        SpringApplication.run(ModelApplication.class, args);
    }
}
