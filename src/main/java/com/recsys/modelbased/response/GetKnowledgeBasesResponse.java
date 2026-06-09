package com.recsys.modelbased.response;

import java.util.List;

import com.recsys.modelbased.vo.KnowledgeBaseVO;

public record GetKnowledgeBasesResponse(List<KnowledgeBaseVO> knowledgeBases) {}
