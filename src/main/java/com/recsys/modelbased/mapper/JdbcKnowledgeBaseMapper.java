package com.recsys.modelbased.mapper;

import com.recsys.modelbased.entity.KnowledgeBase;
import com.recsys.mysql.MySqlClient;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

public class JdbcKnowledgeBaseMapper implements KnowledgeBaseMapper {

    private static final String TABLE = "knowledge_base";

    private final MySqlClient mySqlClient;

    public JdbcKnowledgeBaseMapper(MySqlClient mySqlClient) {
        this.mySqlClient = mySqlClient;
    }

    @Override
    public int insert(KnowledgeBase kb) throws SQLException {
        String sql = "INSERT INTO " + TABLE +
                " (id, name, description, metadata, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)";
        return mySqlClient.execute(sql, List.of(
                kb.getId(),
                kb.getName(),
                kb.getDescription(),
                kb.getMetadata(),
                toTimestamp(kb.getCreatedAt()),
                toTimestamp(kb.getUpdatedAt())
        ));
    }

    @Override
    public KnowledgeBase selectById(String id) throws SQLException {
        String sql = "SELECT id, name, description, metadata, created_at, updated_at FROM " + TABLE +
                " WHERE id = ?";
        List<KnowledgeBase> rows = mySqlClient.query(sql, List.of(id), JdbcKnowledgeBaseMapper::mapRow);
        return rows.isEmpty() ? null : rows.get(0);
    }

    @Override
    public List<KnowledgeBase> selectAll() throws SQLException {
        String sql = "SELECT id, name, description, metadata, created_at, updated_at FROM " + TABLE;
        return mySqlClient.query(sql, Collections.emptyList(), JdbcKnowledgeBaseMapper::mapRow);
    }

    @Override
    public List<KnowledgeBase> selectByIdBatch(List<String> ids) throws SQLException {
        if (ids == null || ids.isEmpty()) {
            return Collections.emptyList();
        }
        String placeholders = ids.stream().map(x -> "?").collect(Collectors.joining(", "));
        String sql = "SELECT id, name, description, metadata, created_at, updated_at FROM " + TABLE +
                " WHERE id IN (" + placeholders + ")";
        return mySqlClient.query(sql, new ArrayList<>(ids), JdbcKnowledgeBaseMapper::mapRow);
    }

    @Override
    public int deleteById(String id) throws SQLException {
        return mySqlClient.execute("DELETE FROM " + TABLE + " WHERE id = ?", List.of(id));
    }

    @Override
    public int updateById(KnowledgeBase kb) throws SQLException {
        String sql = "UPDATE " + TABLE +
                " SET name = ?, description = ?, metadata = ?, updated_at = ? WHERE id = ?";
        return mySqlClient.execute(sql, List.of(
                kb.getName(),
                kb.getDescription(),
                kb.getMetadata(),
                toTimestamp(kb.getUpdatedAt()),
                kb.getId()
        ));
    }

    private static KnowledgeBase mapRow(ResultSet rs) throws SQLException {
        return KnowledgeBase.builder()
                .id(rs.getString("id"))
                .name(rs.getString("name"))
                .description(rs.getString("description"))
                .metadata(rs.getString("metadata"))
                .createdAt(toLocalDateTime(rs.getTimestamp("created_at")))
                .updatedAt(toLocalDateTime(rs.getTimestamp("updated_at")))
                .build();
    }

    private static Timestamp toTimestamp(LocalDateTime ldt) {
        return ldt != null ? Timestamp.valueOf(ldt) : null;
    }

    private static LocalDateTime toLocalDateTime(Timestamp ts) {
        return ts != null ? ts.toLocalDateTime() : null;
    }
}
