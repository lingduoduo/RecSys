package com.recsys.model.knowledge;
import com.recsys.domain.knowledge.CreateKnowledgeBaseResponse;
import com.recsys.domain.knowledge.GetKnowledgeBasesResponse;
import com.recsys.domain.knowledge.KnowledgeBaseVO;
import com.recsys.application.knowledge.KnowledgeBaseFacadeService;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.exception.GlobalExceptionHandler;
import com.recsys.config.RequestScopeData;
import com.recsys.application.auth.LoginTokenService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(KnowledgeBaseController.class)
@Import(GlobalExceptionHandler.class)
class KnowledgeBaseControllerTest {

    @Autowired MockMvc mockMvc;
    @Autowired ObjectMapper objectMapper;
    @MockBean KnowledgeBaseFacadeService service;
    @MockBean LoginTokenService loginTokenService;
    @MockBean RequestScopeData requestScopeData;

    // --- GET /knowledge-bases ---

    @Test
    void getKnowledgeBases_returns200WithList() throws Exception {
        KnowledgeBaseVO vo = new KnowledgeBaseVO("id-1", "My KB", "A description");
        when(service.getKnowledgeBases()).thenReturn(new GetKnowledgeBasesResponse(List.of(vo)));

        mockMvc.perform(get("/api/v1/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.knowledgeBases[0].id").value("id-1"))
                .andExpect(jsonPath("$.data.knowledgeBases[0].name").value("My KB"));
    }

    @Test
    void getKnowledgeBases_empty_returnsEmptyList() throws Exception {
        when(service.getKnowledgeBases()).thenReturn(new GetKnowledgeBasesResponse(List.of()));

        mockMvc.perform(get("/api/v1/knowledge-bases"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.knowledgeBases").isArray())
                .andExpect(jsonPath("$.data.knowledgeBases").isEmpty());
    }

    // --- POST /knowledge-bases ---

    @Test
    void createKnowledgeBase_validRequest_returns200WithVO() throws Exception {
        KnowledgeBaseVO vo = new KnowledgeBaseVO("id-1", "My KB", "desc");
        when(service.createKnowledgeBase(any())).thenReturn(new CreateKnowledgeBaseResponse(vo));

        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"My KB\",\"description\":\"desc\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true))
                .andExpect(jsonPath("$.data.knowledgeBase.id").value("id-1"))
                .andExpect(jsonPath("$.data.knowledgeBase.name").value("My KB"));
    }

    @Test
    void createKnowledgeBase_missingName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"description\":\"desc\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation failed"))
                .andExpect(jsonPath("$.violations[0].field").value("name"));
    }

    @Test
    void createKnowledgeBase_blankName_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("validation failed"));
    }

    @Test
    void createKnowledgeBase_malformedJson_returns400() throws Exception {
        mockMvc.perform(post("/api/v1/knowledge-bases")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{not-json}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("malformed request body"));
    }

    // --- DELETE /knowledge-bases/{id} ---

    @Test
    void deleteKnowledgeBase_returns200() throws Exception {
        mockMvc.perform(delete("/api/v1/knowledge-bases/id-1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).deleteKnowledgeBase("id-1");
    }

    @Test
    void deleteKnowledgeBase_notFound_returns400() throws Exception {
        doThrow(new IllegalArgumentException("Knowledge base not found: id-99"))
                .when(service).deleteKnowledgeBase("id-99");

        mockMvc.perform(delete("/api/v1/knowledge-bases/id-99"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Knowledge base not found: id-99"));
    }

    // --- PATCH /knowledge-bases/{id} ---

    @Test
    void updateKnowledgeBase_returns200() throws Exception {
        mockMvc.perform(patch("/api/v1/knowledge-bases/id-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.success").value(true));

        verify(service).updateKnowledgeBase(eq("id-1"), any());
    }

    @Test
    void updateKnowledgeBase_notFound_returns400() throws Exception {
        doThrow(new IllegalArgumentException("Knowledge base not found: id-99"))
                .when(service).updateKnowledgeBase(eq("id-99"), any());

        mockMvc.perform(patch("/api/v1/knowledge-bases/id-99")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Updated\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error").value("Knowledge base not found: id-99"));
    }
}
