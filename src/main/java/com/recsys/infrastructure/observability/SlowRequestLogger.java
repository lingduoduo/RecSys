package com.recsys.infrastructure.observability;

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Function;

/**
 * Emits one WARN per slow or failed request, for Splunk.
 *
 * <p>Deliberately silent for fast, successful requests. The HEC appender is at-most-once over a
 * bounded, drop-on-full queue: an event per request would push it into a régime where it discards
 * indiscriminately, taking the ERROR events the runbook searches depend on with it and firing
 * SplunkHecDroppingEvents as a side effect of an observability feature. Splunk's job here is
 * "show me the slow ones"; the distribution belongs to the Prometheus histograms.
 */
public final class SlowRequestLogger {

    private static final Logger log = LoggerFactory.getLogger(SlowRequestLogger.class);

    private SlowRequestLogger() {}

    public static Function<? super HttpService, ? extends HttpService> newDecorator(
            String serviceName, long thresholdMs) {

        // Logged once, not per request: emitIfNoteworthy runs inside a whenComplete().thenAccept()
        // callback, so any throw there is caught by the CompletableFuture and would otherwise be
        // discarded with no trace anywhere -- an observability component failing invisibly. If a
        // bug hits every slow/failed request, a per-request WARN would flood; one AtomicBoolean per
        // decorator (i.e. per service) keeps the breadcrumb without the flood, matching
        // GatewayOriginSecret's newDecorator.
        AtomicBoolean warned = new AtomicBoolean();

        return delegate -> (ctx, req) -> {
            ctx.log().whenComplete().thenAccept(requestLog -> {
                try {
                    emitIfNoteworthy(requestLog, ctx.config().route().patternString(), serviceName,
                            thresholdMs);
                } catch (RuntimeException e) {
                    if (warned.compareAndSet(false, true)) {
                        log.warn("Slow-request Splunk event emission failed for {} (first "
                                        + "occurrence, further failures are not logged); the "
                                        + "request itself was not affected.",
                                serviceName, e);
                    }
                }
            });
            return delegate.serve(ctx, req);
        };
    }

    private static void emitIfNoteworthy(RequestLog requestLog, String route,
                                         String serviceName, long thresholdMs) {
        long durationMs = TimeUnit.NANOSECONDS.toMillis(requestLog.totalDurationNanos());
        int statusCode = requestLog.responseStatus().code();

        String outcome = RequestOutcome.classify(statusCode, durationMs, thresholdMs);
        if (outcome == null) {
            return;
        }

        MDC.put("service", serviceName);
        MDC.put("route", route);
        MDC.put("httpMethod", requestLog.requestHeaders().method().name());
        MDC.put("statusCode", Integer.toString(statusCode));
        MDC.put("outcome", outcome);
        MDC.put("durationMs", Long.toString(durationMs));
        try {
            Throwable cause = requestLog.responseCause();
            if (cause != null) {
                log.warn("{} {} completed in {}ms with status {} ({})",
                        MDC.get("httpMethod"), route, durationMs, statusCode, outcome, cause);
            } else {
                log.warn("{} {} completed in {}ms with status {} ({})",
                        MDC.get("httpMethod"), route, durationMs, statusCode, outcome);
            }
        } finally {
            // The completion callback runs on a pooled event loop thread; a leaked entry would
            // attach itself to every later log line that thread emits.
            RequestOutcome.MDC_KEYS.forEach(MDC::remove);
        }
    }
}
