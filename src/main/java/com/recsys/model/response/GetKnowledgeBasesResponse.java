package com.recsys.model.response;

import java.util.List;

import com.recsys.model.vo.KnowledgeBaseVO;

public record GetKnowledgeBasesResponse(List<KnowledgeBaseVO> knowledgeBases) {}
