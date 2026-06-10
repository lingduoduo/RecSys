package com.recsys.model;

import com.recsys.featureflags.config.FeatureFlagConfig;
import com.recsys.model.config.ABTestConfig;
import com.recsys.model.config.HealthProperties;
import com.recsys.model.config.RecommendationCacheProperties;
import com.recsys.model.config.LoginProperties;
import com.recsys.model.config.SubmitTokenProperties;
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
