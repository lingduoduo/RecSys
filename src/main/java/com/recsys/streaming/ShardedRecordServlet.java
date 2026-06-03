package com.recsys.streaming;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.recsys.infrastructure.redis.sharding.Page;
import com.recsys.infrastructure.redis.sharding.RecordType;
import com.recsys.infrastructure.redis.sharding.ShardCursor;
import com.recsys.infrastructure.redis.sharding.ShardedRecord;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import com.recsys.infrastructure.redis.sharding.WriteResult;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * HTTP façade over ShardedRecordStore.
 *
 * POST /shards/records              — write a record (EVENT | FEATURE | LOG)
 * GET  /shards/device?deviceId=X    — per-device cursor read
 * GET  /shards/shard?index=N        — shard-level stream scan
 */
public final class ShardedRecordServlet extends ApiServlet {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final ShardedRecordStore store;

    public ShardedRecordServlet(ShardedRecordStore store) {
        this.store = store;
    }

    // ── POST /shards/records ─────────────────────────────────────────────────

    @Override
    protected void doPost(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        prepareJson(response);
        String path = request.getPathInfo();
        if (path == null || path.equals("/") || path.equals("/records")) {
            handleWrite(request, response);
        } else {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "unknown path: " + path);
        }
    }

    private void handleWrite(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        JsonNode body;
        try {
            body = MAPPER.readTree(request.getReader());
        } catch (Exception e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "invalid JSON body");
            return;
        }

        String deviceId = textField(body, "deviceId");
        String typeStr  = textField(body, "type");
        String eventId  = textField(body, "eventId");
        String payload  = body.has("payload") ? body.get("payload").asText("") : "";

        if (deviceId == null || deviceId.isBlank()) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "missing required field: deviceId");
            return;
        }
        if (eventId == null || eventId.isBlank()) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "missing required field: eventId");
            return;
        }

        RecordType type;
        try {
            type = typeStr != null ? RecordType.valueOf(typeStr.toUpperCase()) : RecordType.EVENT;
        } catch (IllegalArgumentException e) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST,
                    "invalid type '" + typeStr + "' — must be EVENT, FEATURE, or LOG");
            return;
        }

        ShardedRecord record = new ShardedRecord(
                deviceId, 0, type, eventId, payload, System.currentTimeMillis());
        WriteResult result = store.write(record);

        writeJson(response, HttpServletResponse.SC_OK, Map.of(
                "seqNum",     result.seqNum(),
                "shardIndex", result.shardIndex(),
                "status",     result.status().name()
        ));
    }

    // ── GET /shards/device and /shards/shard ─────────────────────────────────

    @Override
    protected void doGet(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        prepareJson(response);
        String path = request.getPathInfo();
        if (path == null) path = "/";

        if (path.startsWith("/device")) {
            handleReadDevice(request, response);
        } else if (path.startsWith("/shard")) {
            handleReadShard(request, response);
        } else {
            writeError(response, HttpServletResponse.SC_NOT_FOUND, "unknown path: " + path);
        }
    }

    private void handleReadDevice(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        String deviceId = request.getParameter("deviceId");
        if (deviceId == null || deviceId.isBlank()) {
            writeError(response, HttpServletResponse.SC_BAD_REQUEST, "missing required param: deviceId");
            return;
        }
        int limit        = optionalIntParam(request, "limit", 10, 1, 100);
        String cursorVal = request.getParameter("cursor");
        ShardCursor cursor = (cursorVal == null || cursorVal.isBlank())
                ? ShardCursor.start() : ShardCursor.of(cursorVal);

        Page<ShardedRecord> page = store.readDevice(deviceId, cursor, limit);
        writeJson(response, HttpServletResponse.SC_OK, Map.of(
                "deviceId", deviceId,
                "cursor",   page.hasMore() ? page.next().value() : "",
                "hasMore",  page.hasMore(),
                "count",    page.records().size(),
                "records",  toMaps(page.records())
        ));
    }

    private void handleReadShard(HttpServletRequest request, HttpServletResponse response)
            throws IOException {
        int shardIndex   = optionalIntParam(request, "index", 0, 0, Integer.MAX_VALUE);
        int limit        = optionalIntParam(request, "limit", 10, 1, 100);
        String cursorVal = request.getParameter("cursor");
        ShardCursor cursor = (cursorVal == null || cursorVal.isBlank())
                ? ShardCursor.start() : ShardCursor.of(cursorVal);

        Page<ShardedRecord> page = store.readShard(shardIndex, cursor, limit);
        writeJson(response, HttpServletResponse.SC_OK, Map.of(
                "shardIndex", shardIndex,
                "cursor",     page.hasMore() ? page.next().value() : "",
                "hasMore",    page.hasMore(),
                "count",      page.records().size(),
                "records",    toMaps(page.records())
        ));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static List<Map<String, Object>> toMaps(List<ShardedRecord> records) {
        return records.stream().map(r -> {
            Map<String, Object> m = new java.util.LinkedHashMap<>();
            m.put("deviceId",  r.deviceId());
            m.put("seqNum",    r.seqNum());
            m.put("type",      r.type().name());
            m.put("eventId",   r.eventId());
            m.put("payload",   r.payload() != null ? r.payload() : "");
            m.put("timestamp", r.timestamp());
            return m;
        }).toList();
    }

    private static String textField(JsonNode node, String field) {
        return node.has(field) ? node.get(field).asText(null) : null;
    }
}
