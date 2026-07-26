package com.recsys.api.serving;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.exc.MismatchedInputException;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.common.ResponseHeadersBuilder;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.recsys.application.retrieval.multichannel.RecallResult.DegradationOutcome;
import com.linecorp.armeria.server.AbstractHttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

public abstract class BaseApiService extends AbstractHttpService {

    protected static final ObjectMapper MAPPER = new ObjectMapper();
    protected final Logger log = LoggerFactory.getLogger(getClass());

    protected static HttpResponse writeJson(HttpStatus status, Object payload) {
        try {
            return HttpResponse.of(status, MediaType.JSON_UTF_8, MAPPER.writeValueAsBytes(payload));
        } catch (Exception e) {
            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.JSON_UTF_8,
                    "{\"error\":\"serialization error\"}");
        }
    }

    /**
     * Serialize {@code payload} once, derive a strong ETag from those exact bytes, and return
     * either 304 (when the client's If-None-Match matches) or 200 with cache headers.
     *
     * <p>Only for non-personalized, shared responses. Anything keyed by user identity must use
     * {@link #writeNoStoreJson}. See
     * docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md.
     */
    protected static HttpResponse writeCacheableJson(HttpStatus status, Object payload,
                                                     String cacheControl, HttpRequest req) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            String etag = HttpCaching.etagFor(body);

            if (HttpCaching.matches(req.headers().get(HttpHeaderNames.IF_NONE_MATCH), etag)) {
                return HttpResponse.of(ResponseHeaders.builder(HttpStatus.NOT_MODIFIED)
                        .set(HttpHeaderNames.CACHE_CONTROL, cacheControl)
                        .set(HttpHeaderNames.ETAG, etag)
                        .build());
            }

            ResponseHeaders headers = ResponseHeaders.builder(status)
                    .contentType(MediaType.JSON_UTF_8)
                    .set(HttpHeaderNames.CACHE_CONTROL, cacheControl)
                    .set(HttpHeaderNames.ETAG, etag)
                    .build();
            return HttpResponse.of(headers, HttpData.wrap(body));
        } catch (Exception e) {
            return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "serialization error");
        }
    }

    /** Serialize {@code payload} with {@code Cache-Control: no-store}. Never cached anywhere. */
    protected static HttpResponse writeNoStoreJson(HttpStatus status, Object payload) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            ResponseHeaders headers = ResponseHeaders.builder(status)
                    .contentType(MediaType.JSON_UTF_8)
                    .set(HttpHeaderNames.CACHE_CONTROL, HttpCaching.NO_STORE)
                    .build();
            return HttpResponse.of(headers, HttpData.wrap(body));
        } catch (Exception e) {
            return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "serialization error");
        }
    }

    /**
     * Like {@link #writeJson} but adds the compatibility
     * {@code X-Recall-Degraded: <comma-joined>} header for degraded channels and
     * the bounded {@code X-Recall-Degradation-Reason} for non-healthy outcomes.
     * Neither signal changes the response status or body.
     *
     * <p>Channel names are sorted before joining. {@code degradedChannels} typically arrives
     * as (or backed by) {@code Set.copyOf(...)} (see {@code RecallResult}), whose iteration
     * order is salted per-JVM-run and not stable across restarts — sorting keeps the header
     * value deterministic for callers and tests instead of varying process to process.
     */
    protected static HttpResponse writeJsonWithRecallDegraded(HttpStatus status, Object payload,
                                                              Set<String> degradedChannels,
                                                              DegradationOutcome outcome) {
        Objects.requireNonNull(outcome, "outcome");
        boolean hasDegradedChannels = degradedChannels != null && !degradedChannels.isEmpty();
        if (!hasDegradedChannels && outcome == DegradationOutcome.HEALTHY) {
            return writeJson(status, payload);
        }
        try {
            byte[] body = MAPPER.writeValueAsBytes(payload);
            ResponseHeadersBuilder headers = ResponseHeaders.builder(status)
                    .contentType(MediaType.JSON_UTF_8);
            if (hasDegradedChannels) {
                String headerValue = degradedChannels.stream().sorted()
                        .collect(Collectors.joining(","));
                headers.set(HttpHeaderNames.of("x-recall-degraded"), headerValue);
            }
            if (outcome != DegradationOutcome.HEALTHY) {
                headers.set(HttpHeaderNames.of("x-recall-degradation-reason"), outcome.wireValue());
            }
            return HttpResponse.of(headers.build(), HttpData.wrap(body));
        } catch (Exception e) {
            return writeError(HttpStatus.INTERNAL_SERVER_ERROR, "serialization error");
        }
    }

    protected static HttpResponse writeError(HttpStatus status, String message) {
        return writeJson(status, Map.of("error", message == null ? "" : message));
    }

    /**
     * Same body as {@link #writeError(HttpStatus, String)} but with {@code Cache-Control: no-store}
     * instead of no cache header at all. For error branches on otherwise-cacheable routes, where
     * CloudFront's default Error Caching Minimum TTL (10 s) would pin the error response at the
     * edge if we didn't opt out explicitly. See
     * docs/superpowers/specs/2026-07-14-cdn-edge-acceleration-design.md.
     */
    protected static HttpResponse writeNoStoreError(HttpStatus status, String message) {
        return writeNoStoreJson(status, Map.of("error", message == null ? "" : message));
    }

    protected static HttpResponse writeError(HttpStatus status, String message, String field, int value) {
        return writeJson(status, Map.of("error", message == null ? "" : message, field, value));
    }

    protected static HttpResponse writeErrorWithRetryAfter(HttpStatus status, String message, int retryAfterSeconds) {
        try {
            byte[] body = MAPPER.writeValueAsBytes(Map.of("error", message == null ? "" : message));
            ResponseHeaders headers = ResponseHeaders.builder(status)
                    .contentType(MediaType.JSON_UTF_8)
                    .set(HttpHeaderNames.RETRY_AFTER, String.valueOf(retryAfterSeconds))
                    .build();
            return HttpResponse.of(headers, HttpData.wrap(body));
        } catch (Exception e) {
            return writeError(status, message);
        }
    }

    protected static <T> T readJsonBody(AggregatedHttpRequest agg, Class<T> bodyType) throws IOException {
        if (agg.content().isEmpty()) {
            throw new BadRequestException("empty request body");
        }
        try {
            return MAPPER.readValue(agg.content().toInputStream(), bodyType);
        } catch (MismatchedInputException e) {
            throw new BadRequestException("empty or invalid json request body");
        } catch (IOException e) {
            throw new BadRequestException("invalid json request body");
        }
    }

    protected static int requiredIntParam(ServiceRequestContext ctx, String name) {
        String value = ctx.queryParam(name);
        if (value == null || value.isBlank()) {
            throw new BadRequestException("missing required query parameter: " + name);
        }
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("invalid numeric parameter format");
        }
    }

    protected static int optionalIntParam(ServiceRequestContext ctx, String name,
                                          int defaultValue, int min, int max) {
        String value = ctx.queryParam(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min) return defaultValue;
            return Math.min(parsed, max);
        } catch (NumberFormatException e) {
            throw new BadRequestException("invalid numeric parameter format");
        }
    }

    /** Parses an optional bounded integer and rejects, rather than clamps, invalid values. */
    protected static Integer optionalBoundedIntParam(ServiceRequestContext ctx, String name,
                                                     Integer defaultValue, int min, int max) {
        String value = ctx.queryParam(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            int parsed = Integer.parseInt(value.trim());
            if (parsed < min || parsed > max) {
                throw new BadRequestException(name + " must be between " + min + " and " + max);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new BadRequestException("invalid numeric parameter format");
        }
    }

    protected static long optionalLongParam(ServiceRequestContext ctx, String name, long defaultValue) {
        String value = ctx.queryParam(name);
        if (value == null || value.isBlank()) return defaultValue;
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException e) {
            throw new BadRequestException("invalid numeric parameter format");
        }
    }

    protected static final class BadRequestException extends RuntimeException {
        protected BadRequestException(String message) { super(message); }
    }
}
