package com.recsys.api.rest;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class RecommendationControllerRegressionTest {

    @Autowired MockMvc mockMvc;

    @Test
    void oldApiV1Recommend_stillReturnsLegacyShape() throws Exception {
        mockMvc.perform(post("/api/v1/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"123\",\"k\":5}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId").value("123"))
                .andExpect(jsonPath("$.modelVersion").exists())
                .andExpect(jsonPath("$.abTestVariant").exists())
                .andExpect(jsonPath("$.recommendations").isArray());
    }
}
