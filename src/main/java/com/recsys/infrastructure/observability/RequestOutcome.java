package com.recsys.infrastructure.observability;

import java.util.Set;

/**
 * The single definition of which requests earn a Splunk event, shared by the Armeria decorator
 * and the Spring interceptor so the four services cannot disagree about what "slow" means.
 */
public final class RequestOutcome {

    /** MDC field names both emitters write. Must not intersect the HEC serializer's reserved set. */
    public static final Set<String> MDC_KEYS =
            Set.of("service", "route", "httpMethod", "statusCode", "outcome", "durationMs");

    private RequestOutcome() {}

    /**
     * @return {@code "slow"}, {@code "failed"}, or {@code null} when the request warrants no event.
     */
    public static String classify(int statusCode, long durationMs, long thresholdMs) {
        if (statusCode >= 500) {
            return "failed";
        }
        if (durationMs > thresholdMs) {
            return "slow";
        }
        return null;
    }
}
