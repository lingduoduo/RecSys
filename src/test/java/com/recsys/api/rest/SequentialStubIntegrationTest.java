package com.recsys.api.rest;

import com.recsys.application.model.ModelRuntimeProvider;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@SpringBootTest
@AutoConfigureMockMvc
class SequentialStubIntegrationTest {

    @Autowired MockMvc mockMvc;
    @MockBean ModelRuntimeProvider modelRuntimeProvider;

    @Test
    void sequentialEndpoint_returns501NotImplemented() throws Exception {
        mockMvc.perform(post("/v2/sequential/recommend")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"userId\":\"u1\",\"limit\":5,\"excludedItemIds\":[],\"cursor\":null}"))
                .andExpect(status().isNotImplemented())
                .andExpect(jsonPath("$.error", containsString("not yet implemented")));
    }
}
