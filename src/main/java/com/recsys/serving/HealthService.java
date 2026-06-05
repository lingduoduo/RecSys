package com.recsys.serving;

import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.util.Map;

public class HealthService extends BaseApiService {
    @Override
    protected HttpResponse doGet(ServiceRequestContext ctx, HttpRequest req) {
        return writeJson(HttpStatus.OK, Map.of("ok", true));
    }
}
