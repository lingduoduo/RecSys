package com.recsys.infrastructure.store;

import com.fasterxml.jackson.databind.JsonNode;
import com.recsys.infrastructure.redis.sharding.Page;
import com.recsys.infrastructure.redis.sharding.RecordType;
import com.recsys.infrastructure.redis.sharding.ShardCursor;
import com.recsys.infrastructure.redis.sharding.ShardedRecord;
import com.recsys.infrastructure.redis.sharding.ShardedRecordStore;
import com.recsys.infrastructure.redis.sharding.WriteResult;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.api.online.ApiService;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * HTTP façade over ShardedRecordStore.
 *
 * POST /shards/records              — write a record (EVENT | FEATURE | LOG)
 * GET  /shards/device?deviceId=X    — per-device cursor read
 * GET  /shards/shard?index=N        — shard-level stream scan
 */
public final class ShardedRecordService extends ApiService {

    private final ShardedRecordStore store;

    public ShardedRecordService(ShardedRecordStore store) {
        this.store = store;
    }

    // ── POST /shards/records ─────────────────────────────────────────────────

    @Override
    protected HttpResponse doPost(ServiceRequestContext ctx, HttpRequest req) {
        String path = ctx.path();
        if (path.endsWith("/records") || path.equals("/shards/") || path.equals("/shards")) {
            return HttpResponse.of(req.aggregate().thenApplyAsync(agg -> {
                try {
                    JsonNode body = MAPPER.readTree(agg.content().toInputStream());

                    String deviceId = textField(body, "deviceId");
                    String typeStr  = textField(body, "type");
                    String eventId  = textField(body, "eventId");
                    String payload  = body.has("payload") ? body.get("payload").asText("") : "";

                    if (deviceId == null || deviceId.isBlank()) {
                        return writeError(HttpStatus.BAD_REQUEST, "missing required field: deviceId");
                    }
                    if (eventId == null || eventId.isBlank()) {
                        return writeError(HttpStatus.BAD_REQUEST, "missing required field: eventId");
                    }

                    RecordType type;
                    try {
                        type = typeStr != null ? RecordType.valueOf(typeStr.toUpperCase()) : RecordType.EVENT;
                    } catch (IllegalArgumentException e) {
                        return writeError(HttpStatus.BAD_REQUEST,
                                "invalid type '" + typeStr + "' — must be EVENT, FEATURE, or LOG");
                    }

                    ShardedRecord record = new ShardedRecord(
                            deviceId, 0, type, eventId, payload, System.currentTimeMillis());
                    WriteResult result = store.write(record);

                    return writeJson(HttpStatus.OK, Map.of(
                            "seqNum",     result.seqNum(),
                            "shardIndex", result.shardIndex(),
                            "status",     result.status().name()
                    ));
                } catch (Exception e) {
                    log.error("Unexpected error in ShardedRecordService POST", e);
                    return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
                }
            }, ctx.blockingTaskExecutor()));
        }
        return writeError(HttpStatus.NOT_FOUND, "unknown path");
    }

    // ── GET /shards/device and /shards/shard ─────────────────────────────────

    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        String path = ctx.path();

        if (path.equals("/shards/device")) {
            return handleReadDevice(ctx);
        } else if (path.equals("/shards/shard")) {
            return handleReadShard(ctx);
        }
        return writeError(HttpStatus.NOT_FOUND, "unknown path");
    }

    // ── GET helpers ──────────────────────────────────────────────────────────

    private HttpResponse handleReadDevice(ServiceRequestContext ctx) {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                String deviceId = ctx.queryParam("deviceId");
                if (deviceId == null || deviceId.isBlank()) {
                    return writeError(HttpStatus.BAD_REQUEST, "missing required param: deviceId");
                }
                int limit        = optionalIntParam(ctx, "limit", 10, 1, 100);
                String cursorVal = ctx.queryParam("cursor");
                ShardCursor cursor = (cursorVal == null || cursorVal.isBlank())
                        ? ShardCursor.start() : ShardCursor.of(cursorVal);

                Page<ShardedRecord> page = store.readDevice(deviceId, cursor, limit);
                return writeJson(HttpStatus.OK, Map.of(
                        "deviceId", deviceId,
                        "cursor",   page.hasMore() ? page.next().value() : "",
                        "hasMore",  page.hasMore(),
                        "count",    page.records().size(),
                        "records",  toMaps(page.records())
                ));
            } catch (Exception e) {
                log.error("Unexpected error in ShardedRecordService GET /device", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }

    private HttpResponse handleReadShard(ServiceRequestContext ctx) {
        return HttpResponse.of(CompletableFuture.supplyAsync(() -> {
            try {
                int shardIndex   = optionalIntParam(ctx, "index", 0, 0, Integer.MAX_VALUE);
                int limit        = optionalIntParam(ctx, "limit", 10, 1, 100);
                String cursorVal = ctx.queryParam("cursor");
                ShardCursor cursor = (cursorVal == null || cursorVal.isBlank())
                        ? ShardCursor.start() : ShardCursor.of(cursorVal);

                Page<ShardedRecord> page = store.readShard(shardIndex, cursor, limit);
                return writeJson(HttpStatus.OK, Map.of(
                        "shardIndex", shardIndex,
                        "cursor",     page.hasMore() ? page.next().value() : "",
                        "hasMore",    page.hasMore(),
                        "count",      page.records().size(),
                        "records",    toMaps(page.records())
                ));
            } catch (Exception e) {
                log.error("Unexpected error in ShardedRecordService GET /shard", e);
                return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "internal server error");
            }
        }, ctx.blockingTaskExecutor()));
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private static List<Map<String, Object>> toMaps(List<ShardedRecord> records) {
        return records.stream().map(r -> {
            Map<String, Object> m = new LinkedHashMap<>();
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
