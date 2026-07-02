package com.recsys.application.gateway;

import com.linecorp.armeria.common.HttpResponse;

/** Outcome of a gateway auth check: either a rejection response, or an allowed principal. */
public final class GatewayAuthResult {
    private final HttpResponse rejection;
    private final GatewayPrincipal principal;

    private GatewayAuthResult(HttpResponse rejection, GatewayPrincipal principal) {
        this.rejection = rejection;
        this.principal = principal;
    }

    public static GatewayAuthResult allowed(GatewayPrincipal principal) {
        return new GatewayAuthResult(null, principal);
    }

    public static GatewayAuthResult rejected(HttpResponse rejection) {
        return new GatewayAuthResult(rejection, null);
    }

    public boolean rejected() {
        return rejection != null;
    }

    public HttpResponse rejection() {
        return rejection;
    }

    public GatewayPrincipal principal() {
        return principal;
    }
}
