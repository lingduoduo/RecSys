package com.recsys.modelbased;

import com.recsys.featureflags.config.FeatureFlagConfig;
import com.recsys.modelbased.config.ABTestConfig;
import com.recsys.modelbased.config.HealthProperties;
import com.recsys.modelbased.config.RecommendationCacheProperties;
import com.recsys.modelbased.config.SubmitTokenProperties;
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
        SubmitTokenProperties.class
})
public class ModelApplication {
    public static void main(String[] args) {
        SpringApplication.run(ModelApplication.class, args);
    }
}
