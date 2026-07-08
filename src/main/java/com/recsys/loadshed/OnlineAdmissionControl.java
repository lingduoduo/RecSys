package com.recsys.loadshed;
import com.recsys.metrics.OnlineServingMetricsService;

import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.linecorp.armeria.server.SimpleDecoratingHttpService;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Rejects expensive online-serving requests before they enter a blocking executor.
 */
public final class OnlineAdmissionControl extends SimpleDecoratingHttpService {
    private static final HttpData OVERLOADED_BODY =
            HttpData.ofUtf8("{\"error\":\"online serving overloaded\",\"reason\":\"concurrency_limit\"}");

    private final OnlineLoadShedder loadShedder;
    private final Runnable onReject;

    /** Backward-compatible: reports rejections to the online metrics service. */
    public OnlineAdmissionControl(HttpService delegate,
                                  OnlineLoadShedder loadShedder,
                                  OnlineServingMetricsService metricsService) {
        this(delegate, loadShedder, metricsService::recordRejected);
    }

    /** General: {@code onReject} runs once per rejected request (e.g. a metrics counter or no-op). */
    public OnlineAdmissionControl(HttpService delegate,
                                  OnlineLoadShedder loadShedder,
                                  Runnable onReject) {
        super(delegate);
        this.loadShedder = loadShedder;
        this.onReject = onReject;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) throws Exception {
        if (!loadShedder.tryAcquire()) {
            onReject.run();
            ResponseHeaders headers = ResponseHeaders.builder(HttpStatus.TOO_MANY_REQUESTS)
                    .contentType(MediaType.JSON_UTF_8)
                    .set(HttpHeaderNames.RETRY_AFTER,
                            String.valueOf(Math.max(1, loadShedder.retryAfterSeconds())))
                    .build();
            return HttpResponse.of(headers, OVERLOADED_BODY);
        }

        AtomicBoolean released = new AtomicBoolean();
        try {
            HttpResponse response = unwrap().serve(ctx, req);
            response.whenComplete().whenComplete((unused, cause) -> releaseOnce(released));
            return response;
        } catch (Exception | Error e) {
            releaseOnce(released);
            throw e;
        }
    }

    private void releaseOnce(AtomicBoolean released) {
        if (released.compareAndSet(false, true)) {
            loadShedder.release();
        }
    }
}
