package com.recsys.model.converter;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.model.dto.KnowledgeBaseDTO;
import com.recsys.model.entity.KnowledgeBase;
import com.recsys.model.request.CreateKnowledgeBaseRequest;
import com.recsys.model.request.UpdateKnowledgeBaseRequest;
import com.recsys.model.vo.KnowledgeBaseVO;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class KnowledgeBaseConverterTest {

    private KnowledgeBaseConverter converter;

    @BeforeEach
    void setUp() {
        converter = new KnowledgeBaseConverter(new ObjectMapper());
    }

    @Test
    void toDTO_fromCreateRequest_mapsAllFields() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("My KB");
        request.setDescription("A test knowledge base");
        request.setVersion("1.0");

        KnowledgeBaseDTO dto = converter.toDTO(request);

        assertThat(dto.getId()).isNotBlank();
        assertThat(dto.getName()).isEqualTo("My KB");
        assertThat(dto.getDescription()).isEqualTo("A test knowledge base");
        assertThat(dto.getMetadata().getVersion()).isEqualTo("1.0");
        assertThat(dto.getCreatedAt()).isNotNull();
        assertThat(dto.getUpdatedAt()).isNotNull();
    }

    @Test
    void toDTO_fromCreateRequest_nullVersion_leavesMetadataNull() {
        CreateKnowledgeBaseRequest request = new CreateKnowledgeBaseRequest();
        request.setName("My KB");

        KnowledgeBaseDTO dto = converter.toDTO(request);

        assertThat(dto.getMetadata()).isNull();
    }

    @Test
    void toDTO_fromCreateRequest_nullRequest_throws() {
        assertThatThrownBy(() -> converter.toDTO((CreateKnowledgeBaseRequest) null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("CreateKnowledgeBaseRequest cannot be null");
    }

    @Test
    void toEntity_serializesMetadataToJson() throws Exception {
        KnowledgeBaseDTO dto = KnowledgeBaseDTO.builder()
                .id("id-1")
                .name("KB")
                .description("desc")
                .metadata(new KnowledgeBaseDTO.MetaData("2.0"))
                .createdAt(LocalDateTime.now())
                .updatedAt(LocalDateTime.now())
                .build();

        KnowledgeBase entity = converter.toEntity(dto);

        assertThat(entity.getId()).isEqualTo("id-1");
        assertThat(entity.getName()).isEqualTo("KB");
        assertThat(entity.getMetadata()).contains("\"version\"").contains("2.0");
    }

    @Test
    void toEntity_nullMetadata_storesNullMetadata() throws Exception {
        KnowledgeBaseDTO dto = KnowledgeBaseDTO.builder()
                .id("id-1")
                .name("KB")
                .build();

        KnowledgeBase entity = converter.toEntity(dto);

        assertThat(entity.getMetadata()).isNull();
    }

    @Test
    void toDTO_fromEntity_deserializesMetadataFromJson() throws Exception {
        LocalDateTime now = LocalDateTime.now();
        KnowledgeBase entity = KnowledgeBase.builder()
                .id("id-1")
                .name("KB")
                .description("desc")
                .metadata("{\"version\":\"3.0\"}")
                .createdAt(now)
                .updatedAt(now)
                .build();

        KnowledgeBaseDTO dto = converter.toDTO(entity);

        assertThat(dto.getId()).isEqualTo("id-1");
        assertThat(dto.getName()).isEqualTo("KB");
        assertThat(dto.getMetadata().getVersion()).isEqualTo("3.0");
    }

    @Test
    void toVO_mapsIdNameDescription() {
        KnowledgeBaseDTO dto = KnowledgeBaseDTO.builder()
                .id("id-1")
                .name("KB")
                .description("desc")
                .build();

        KnowledgeBaseVO vo = converter.toVO(dto);

        assertThat(vo.id()).isEqualTo("id-1");
        assertThat(vo.name()).isEqualTo("KB");
        assertThat(vo.description()).isEqualTo("desc");
    }

    @Test
    void updateDTOFromRequest_patchesOnlyPresentFields() {
        KnowledgeBaseDTO dto = KnowledgeBaseDTO.builder()
                .id("id-1")
                .name("OldName")
                .description("OldDesc")
                .build();

        UpdateKnowledgeBaseRequest request = new UpdateKnowledgeBaseRequest();
        request.setName("NewName");

        converter.updateDTOFromRequest(dto, request);

        assertThat(dto.getName()).isEqualTo("NewName");
        assertThat(dto.getDescription()).isEqualTo("OldDesc");
        assertThat(dto.getUpdatedAt()).isNotNull();
    }

    @Test
    void updateDTOFromRequest_allFields_patchesAll() {
        KnowledgeBaseDTO dto = KnowledgeBaseDTO.builder()
                .id("id-1")
                .name("OldName")
                .description("OldDesc")
                .build();

        UpdateKnowledgeBaseRequest request = new UpdateKnowledgeBaseRequest();
        request.setName("NewName");
        request.setDescription("NewDesc");
        request.setVersion("4.0");

        converter.updateDTOFromRequest(dto, request);

        assertThat(dto.getName()).isEqualTo("NewName");
        assertThat(dto.getDescription()).isEqualTo("NewDesc");
        assertThat(dto.getMetadata().getVersion()).isEqualTo("4.0");
    }
}
