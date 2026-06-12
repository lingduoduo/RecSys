package com.recsys.model.knowledge;

import com.fasterxml.jackson.core.JsonProcessingException;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

@Service
public class KnowledgeBaseFacadeService {

    private final Map<String, KnowledgeBase> store = new ConcurrentHashMap<>();
    private final KnowledgeBaseConverter converter;

    public KnowledgeBaseFacadeService(KnowledgeBaseConverter converter) {
        this.converter = converter;
    }

    public GetKnowledgeBasesResponse getKnowledgeBases() {
        List<KnowledgeBaseVO> vos = new ArrayList<>();
        for (KnowledgeBase entity : store.values()) {
            try {
                vos.add(converter.toVO(entity));
            } catch (JsonProcessingException e) {
                throw new IllegalStateException("Failed to convert knowledge base: " + entity.getId(), e);
            }
        }
        return new GetKnowledgeBasesResponse(vos);
    }

    public CreateKnowledgeBaseResponse createKnowledgeBase(CreateKnowledgeBaseRequest request) {
        KnowledgeBaseDTO dto = converter.toDTO(request);
        try {
            KnowledgeBase entity = converter.toEntity(dto);
            store.put(entity.getId(), entity);
            return new CreateKnowledgeBaseResponse(converter.toVO(dto));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to create knowledge base", e);
        }
    }

    public void deleteKnowledgeBase(String knowledgeBaseId) {
        if (!store.containsKey(knowledgeBaseId)) {
            throw new IllegalArgumentException("Knowledge base not found: " + knowledgeBaseId);
        }
        store.remove(knowledgeBaseId);
    }

    public void updateKnowledgeBase(String knowledgeBaseId, UpdateKnowledgeBaseRequest request) {
        KnowledgeBase entity = store.get(knowledgeBaseId);
        if (entity == null) {
            throw new IllegalArgumentException("Knowledge base not found: " + knowledgeBaseId);
        }
        try {
            KnowledgeBaseDTO dto = converter.toDTO(entity);
            converter.updateDTOFromRequest(dto, request);
            store.put(knowledgeBaseId, converter.toEntity(dto));
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to update knowledge base: " + knowledgeBaseId, e);
        }
    }
}
