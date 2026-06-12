package com.recsys.model.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.stereotype.Component;
import org.springframework.util.Assert;

import java.time.LocalDateTime;
import java.util.UUID;

@Component
public class KnowledgeBaseConverter {

    private final ObjectMapper objectMapper;

    public KnowledgeBaseConverter(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
    }

    public KnowledgeBase toEntity(KnowledgeBaseDTO dto) throws JsonProcessingException {
        Assert.notNull(dto, "KnowledgeBaseDTO cannot be null");
        return KnowledgeBase.builder()
                .id(dto.getId())
                .name(dto.getName())
                .description(dto.getDescription())
                .metadata(dto.getMetadata() != null
                        ? objectMapper.writeValueAsString(dto.getMetadata())
                        : null)
                .createdAt(dto.getCreatedAt())
                .updatedAt(dto.getUpdatedAt())
                .build();
    }

    public KnowledgeBaseDTO toDTO(KnowledgeBase entity) throws JsonProcessingException {
        Assert.notNull(entity, "KnowledgeBase cannot be null");
        return KnowledgeBaseDTO.builder()
                .id(entity.getId())
                .name(entity.getName())
                .description(entity.getDescription())
                .metadata(entity.getMetadata() != null
                        ? objectMapper.readValue(entity.getMetadata(), KnowledgeBaseDTO.MetaData.class)
                        : null)
                .createdAt(entity.getCreatedAt())
                .updatedAt(entity.getUpdatedAt())
                .build();
    }

    public KnowledgeBaseVO toVO(KnowledgeBaseDTO dto) {
        Assert.notNull(dto, "KnowledgeBaseDTO cannot be null");
        return new KnowledgeBaseVO(dto.getId(), dto.getName(), dto.getDescription());
    }

    public KnowledgeBaseVO toVO(KnowledgeBase entity) throws JsonProcessingException {
        return toVO(toDTO(entity));
    }

    public KnowledgeBaseDTO toDTO(CreateKnowledgeBaseRequest request) {
        Assert.notNull(request, "CreateKnowledgeBaseRequest cannot be null");
        LocalDateTime now = LocalDateTime.now();
        KnowledgeBaseDTO.MetaData metadata = request.getVersion() != null
                ? new KnowledgeBaseDTO.MetaData(request.getVersion())
                : null;
        return KnowledgeBaseDTO.builder()
                .id(UUID.randomUUID().toString())
                .name(request.getName())
                .description(request.getDescription())
                .metadata(metadata)
                .createdAt(now)
                .updatedAt(now)
                .build();
    }

    public void updateDTOFromRequest(KnowledgeBaseDTO dto, UpdateKnowledgeBaseRequest request) {
        Assert.notNull(dto, "KnowledgeBaseDTO cannot be null");
        Assert.notNull(request, "UpdateKnowledgeBaseRequest cannot be null");
        if (request.getName() != null) {
            dto.setName(request.getName());
        }
        if (request.getDescription() != null) {
            dto.setDescription(request.getDescription());
        }
        if (request.getVersion() != null) {
            dto.setMetadata(new KnowledgeBaseDTO.MetaData(request.getVersion()));
        }
        dto.setUpdatedAt(LocalDateTime.now());
    }
}
