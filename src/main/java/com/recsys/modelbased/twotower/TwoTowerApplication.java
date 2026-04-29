package com.recsys.modelbased.twotower;

import com.recsys.modelbased.twotower.config.ABTestConfig;
import com.recsys.modelbased.twotower.config.HealthProperties;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;

@SpringBootApplication
@EnableConfigurationProperties({HealthProperties.class, ABTestConfig.class})
public class TwoTowerApplication {
    public static void main(String[] args) {
        SpringApplication.run(TwoTowerApplication.class, args);
    }
}
