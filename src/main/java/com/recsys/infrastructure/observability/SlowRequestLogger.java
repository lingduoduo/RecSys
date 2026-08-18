package com.recsys.infrastructure.observability;

import com.linecorp.armeria.common.logging.RequestLog;
import com.linecorp.armeria.server.HttpService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.concurrent.TimeUnit;
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

        return delegate -> (ctx, req) -> {
            ctx.log().whenComplete().thenAccept(requestLog -> emitIfNoteworthy(
                    requestLog, ctx.config().route().patternString(), serviceName, thresholdMs));
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
