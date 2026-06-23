package com.recsys.application.knowledge;
import com.recsys.model.knowledge.KnowledgeBaseConverter;
import com.recsys.domain.knowledge.UpdateKnowledgeBaseRequest;
import com.recsys.domain.knowledge.CreateKnowledgeBaseResponse;
import com.recsys.domain.knowledge.KnowledgeBaseVO;
import com.recsys.domain.knowledge.GetKnowledgeBasesResponse;
import com.recsys.domain.knowledge.KnowledgeBase;
import com.recsys.domain.knowledge.KnowledgeBaseDTO;
import com.recsys.domain.knowledge.CreateKnowledgeBaseRequest;
import com.recsys.application.knowledge.KnowledgeBaseFacadeService;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class KnowledgeBaseFacadeServiceTest {

    @Mock KnowledgeBaseConverter converter;

    private KnowledgeBaseFacadeService service;

    @BeforeEach
    void setUp() {
        service = new KnowledgeBaseFacadeService(converter);
    }

    // --- getKnowledgeBases ---

    @Test
    void getKnowledgeBases_empty_returnsEmptyList() {
        GetKnowledgeBasesResponse response = service.getKnowledgeBases();
        assertThat(response.knowledgeBases()).isEmpty();
    }

    @Test
    void getKnowledgeBases_afterCreate_returnsStoredVO() throws Exception {
        CreateKnowledgeBaseRequest request = createRequest("KB", "desc", "1.0");
        KnowledgeBaseDTO dto = buildDTO("id-1", "KB", "desc");
        KnowledgeBase entity = buildEntity("id-1", "KB");
        KnowledgeBaseVO vo = new KnowledgeBaseVO("id-1", "KB", "desc");

        when(converter.toDTO(request)).thenReturn(dto);
        when(converter.toEntity(dto)).thenReturn(entity);
        when(converter.toVO(dto)).thenReturn(vo);
        when(converter.toVO(entity)).thenReturn(vo);

        service.createKnowledgeBase(request);

        GetKnowledgeBasesResponse response = service.getKnowledgeBases();
        assertThat(response.knowledgeBases()).hasSize(1);
        assertThat(response.knowledgeBases().get(0).id()).isEqualTo("id-1");
    }

    // --- createKnowledgeBase ---

    @Test
    void createKnowledgeBase_storesEntityAndReturnsVO() throws Exception {
        CreateKnowledgeBaseRequest request = createRequest("KB", "desc", "1.0");
        KnowledgeBaseDTO dto = buildDTO("id-1", "KB", "desc");
        KnowledgeBase entity = buildEntity("id-1", "KB");
        KnowledgeBaseVO vo = new KnowledgeBaseVO("id-1", "KB", "desc");

        when(converter.toDTO(request)).thenReturn(dto);
        when(converter.toEntity(dto)).thenReturn(entity);
        when(converter.toVO(dto)).thenReturn(vo);

        CreateKnowledgeBaseResponse response = service.createKnowledgeBase(request);

        assertThat(response.knowledgeBase().id()).isEqualTo("id-1");
        assertThat(response.knowledgeBase().name()).isEqualTo("KB");
    }

    // --- deleteKnowledgeBase ---

    @Test
    void deleteKnowledgeBase_removesEntry() throws Exception {
        seedStore("id-1");

        service.deleteKnowledgeBase("id-1");

        assertThat(service.getKnowledgeBases().knowledgeBases()).isEmpty();
    }

    @Test
    void deleteKnowledgeBase_unknownId_throws() {
        assertThatThrownBy(() -> service.deleteKnowledgeBase("unknown"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    // --- updateKnowledgeBase ---

    @Test
    void updateKnowledgeBase_delegatesToConverter() throws Exception {
        seedStore("id-1");
        KnowledgeBaseDTO dto = buildDTO("id-1", "Old", "OldDesc");
        KnowledgeBase updated = buildEntity("id-1", "New");

        when(converter.toDTO(any(KnowledgeBase.class))).thenReturn(dto);
        doAnswer(inv -> { dto.setName("New"); return null; })
                .when(converter).updateDTOFromRequest(any(), any());
        when(converter.toEntity(dto)).thenReturn(updated);

        UpdateKnowledgeBaseRequest request = new UpdateKnowledgeBaseRequest();
        request.setName("New");

        service.updateKnowledgeBase("id-1", request);

        verify(converter).updateDTOFromRequest(dto, request);
        verify(converter).toEntity(dto);
    }

    @Test
    void updateKnowledgeBase_unknownId_throws() {
        UpdateKnowledgeBaseRequest request = new UpdateKnowledgeBaseRequest();
        assertThatThrownBy(() -> service.updateKnowledgeBase("unknown", request))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("unknown");
    }

    // --- helpers ---

    private void seedStore(String id) throws Exception {
        CreateKnowledgeBaseRequest request = createRequest("KB", "desc", null);
        KnowledgeBaseDTO dto = buildDTO(id, "KB", "desc");
        KnowledgeBase entity = buildEntity(id, "KB");
        KnowledgeBaseVO vo = new KnowledgeBaseVO(id, "KB", "desc");

        when(converter.toDTO(request)).thenReturn(dto);
        when(converter.toEntity(dto)).thenReturn(entity);
        when(converter.toVO(dto)).thenReturn(vo);

        service.createKnowledgeBase(request);
    }

    private static CreateKnowledgeBaseRequest createRequest(String name, String desc, String version) {
        CreateKnowledgeBaseRequest r = new CreateKnowledgeBaseRequest();
        r.setName(name);
        r.setDescription(desc);
        r.setVersion(version);
        return r;
    }

    private static KnowledgeBaseDTO buildDTO(String id, String name, String desc) {
        return KnowledgeBaseDTO.builder()
                .id(id).name(name).description(desc)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }

    private static KnowledgeBase buildEntity(String id, String name) {
        return KnowledgeBase.builder()
                .id(id).name(name)
                .createdAt(LocalDateTime.now()).updatedAt(LocalDateTime.now())
                .build();
    }
}
