package com.recsys.model.controller;

import com.recsys.config.GlobalExceptionHandler;
import com.recsys.model.response.ModelVersionResponse;
import com.recsys.model.service.ModelVersionService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(VersionController.class)
@Import(GlobalExceptionHandler.class)
class VersionControllerTest {

    @Autowired MockMvc mockMvc;
    @MockBean ModelVersionService versionService;

    @Test
    void listVersions_returnsActiveAndLoadedVariants() throws Exception {
        when(versionService.listVersions()).thenReturn(response("training", null));

        mockMvc.perform(get("/api/v1/model/versions"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVariant").value("training"))
                .andExpect(jsonPath("$.variants[0].variant").value("training"))
                .andExpect(jsonPath("$.variants[0].ready").value(true));
    }

    @Test
    void preload_validVariant_returnsVersionSnapshot() throws Exception {
        when(versionService.preload("test")).thenReturn(response("training", null));

        mockMvc.perform(post("/api/v1/model/versions/preload")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variant\":\"test\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVariant").value("training"));
    }

    @Test
    void activate_blankVariant_returnsValidationError() throws Exception {
        mockMvc.perform(post("/api/v1/model/versions/activate")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"variant\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation failed"))
                .andExpect(jsonPath("$.violations[0].field").value("variant"));
    }

    @Test
    void rollback_returnsVersionSnapshot() throws Exception {
        when(versionService.rollback()).thenReturn(response("training", "test"));

        mockMvc.perform(post("/api/v1/model/versions/rollback"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.activeVariant").value("training"))
                .andExpect(jsonPath("$.previousActiveVariant").value("test"));
    }

    @Test
    void rollback_withoutPreviousVariant_returnsBadRequest() throws Exception {
        when(versionService.rollback())
                .thenThrow(new IllegalArgumentException("no previous active model variant to roll back to"));

        mockMvc.perform(post("/api/v1/model/versions/rollback"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("no previous active model variant to roll back to"));
    }

    private static ModelVersionResponse response(String active, String previous) {
        return new ModelVersionResponse(
                active,
                previous,
                List.of(new ModelVersionResponse.VariantVersion("training", "dssm-demo-v1", true, true)));
    }
}
