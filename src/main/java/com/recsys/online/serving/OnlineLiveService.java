package com.recsys.online.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;
import com.recsys.serving.BaseApiService;

import java.util.Map;

/** Liveness reports process viability and never fails merely because the node is draining. */
public final class OnlineLiveService extends BaseApiService {
    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        return writeJson(HttpStatus.OK, Map.of(
                "ok", true,
                "live", true,
                "service", "online-serving"
        ));
    }
}
