package com.recsys.loadshed;

import com.linecorp.armeria.server.ServerBuilder;

/**
 * Shared graceful-shutdown window for the Armeria serving entrypoints. Applying this makes
 * {@code server.stop()} wait for in-flight requests to drain instead of cutting them off.
 */
public final class GracefulServers {

    // 1s quiet period matches the online server's original value. 30s max drain sits below the
    // K8s terminationGracePeriodSeconds: 60 so the pod is never SIGKILLed mid-drain.
    static final long QUIET_PERIOD_MS = 1_000L;
    static final long TIMEOUT_MS = 30_000L;

    private GracefulServers() {}

    /** Applies the standard drain window to the builder and returns it for chaining. */
    public static ServerBuilder applyShutdownWindow(ServerBuilder sb) {
        return sb.gracefulShutdownTimeoutMillis(QUIET_PERIOD_MS, TIMEOUT_MS);
    }
}
