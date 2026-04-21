package com.recsys.training.modelbased.twotower.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Configuration
public class ModelConfig {

    @Bean
    public ConcurrentMap<String, float[]> itemEmbeddingCache() {
        return new ConcurrentHashMap<>();
    }
}
