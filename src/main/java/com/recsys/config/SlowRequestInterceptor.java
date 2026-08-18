package com.recsys.config;

import com.recsys.infrastructure.observability.RequestOutcome;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.HandlerInterceptor;
import org.springframework.web.servlet.HandlerMapping;

import java.util.concurrent.TimeUnit;

/**
 * The model service's equivalent of {@code SlowRequestLogger}: one WARN per slow or failed
 * request. Shares {@link RequestOutcome} with the Armeria decorator so the four services cannot
 * disagree about what "slow" means.
 */
@Component
public class SlowRequestInterceptor implements HandlerInterceptor {

    static final String START_NANOS_ATTRIBUTE = SlowRequestInterceptor.class.getName() + ".start";
    private static final String SERVICE_NAME = "model-serving";

    private static final Logger log = LoggerFactory.getLogger(SlowRequestInterceptor.class);

    private final long thresholdMs;

    public SlowRequestInterceptor(
            @Value("${recsys.observability.slow-request-threshold-ms:${SLOW_REQUEST_LOG_THRESHOLD_MS:500}}")
            long thresholdMs) {
        this.thresholdMs = thresholdMs;
    }

    @Override
    public boolean preHandle(@NonNull HttpServletRequest request,
                             @NonNull HttpServletResponse response,
                             @NonNull Object handler) {
        request.setAttribute(START_NANOS_ATTRIBUTE, System.nanoTime());
        return true;
    }

    @Override
    public void afterCompletion(@NonNull HttpServletRequest request,
                                @NonNull HttpServletResponse response,
                                @NonNull Object handler,
                                Exception ex) {
        Object start = request.getAttribute(START_NANOS_ATTRIBUTE);
        if (!(start instanceof Long startNanos)) {
            return;
        }
        long durationMs = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos);
        int statusCode = response.getStatus();

        String outcome = RequestOutcome.classify(statusCode, durationMs, thresholdMs);
        if (outcome == null) {
            return;
        }

        // The matched pattern, never the raw URI: a path carrying an id would make every
        // request its own route value in Splunk.
        Object pattern = request.getAttribute(HandlerMapping.BEST_MATCHING_PATTERN_ATTRIBUTE);
        String route = pattern instanceof String s ? s : "unmatched";

        MDC.put("service", SERVICE_NAME);
        MDC.put("route", route);
        MDC.put("httpMethod", request.getMethod());
        MDC.put("statusCode", Integer.toString(statusCode));
        MDC.put("outcome", outcome);
        MDC.put("durationMs", Long.toString(durationMs));
        try {
            if (ex != null) {
                log.warn("{} {} completed in {}ms with status {} ({})",
                        request.getMethod(), route, durationMs, statusCode, outcome, ex);
            } else {
                log.warn("{} {} completed in {}ms with status {} ({})",
                        request.getMethod(), route, durationMs, statusCode, outcome);
            }
        } finally {
            // Tomcat threads are pooled and TraceIdAspect also writes MDC on them.
            RequestOutcome.MDC_KEYS.forEach(MDC::remove);
        }
    }
}
