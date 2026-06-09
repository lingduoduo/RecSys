package com.recsys.modelbased.mapper;

import com.recsys.modelbased.entity.KnowledgeBase;

import java.sql.SQLException;
import java.util.List;

/**
 * Data access contract for the {@code knowledge_base} table.
 */
public interface KnowledgeBaseMapper {

    int insert(KnowledgeBase knowledgeBase) throws SQLException;

    KnowledgeBase selectById(String id) throws SQLException;

    List<KnowledgeBase> selectAll() throws SQLException;

    List<KnowledgeBase> selectByIdBatch(List<String> ids) throws SQLException;

    int deleteById(String id) throws SQLException;

    int updateById(KnowledgeBase knowledgeBase) throws SQLException;
}
