package com.recsys.application.gateway;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.linecorp.armeria.common.AggregatedHttpRequest;
import com.linecorp.armeria.common.HttpData;
import com.linecorp.armeria.common.HttpHeaderNames;
import com.linecorp.armeria.common.HttpMethod;
import com.linecorp.armeria.common.HttpRequest;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.common.ResponseHeaders;
import com.linecorp.armeria.server.HttpService;
import com.linecorp.armeria.server.ServiceRequestContext;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

public final class RecommendationGatewayService implements HttpService {
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS);
    static final Map<String, String> STRATEGY_ROUTES = Map.of(
            "embedding", "embed-recall",
            "model", "model-inference",
            "online", "online-blend",
            "sequential", "sequential");
    private static final String DEFAULT_STRATEGY = "model";

    private final Map<String, MicroserviceRoute> routesByName;
    private final GatewayRequestForwarder forwarder;
    private final GatewayAuthenticator authenticator;

    public RecommendationGatewayService(List<MicroserviceRoute> routes,
                                        GatewayRequestForwarder forwarder,
                                        GatewayAuthenticator authenticator) {
        routesByName = List.copyOf(routes).stream().collect(Collectors.toUnmodifiableMap(
                MicroserviceRoute::name, Function.identity()));
        this.forwarder = Objects.requireNonNull(forwarder, "forwarder");
        this.authenticator = authenticator == null
                ? GatewayAuthenticator.disabled() : authenticator;
    }

    @Override
    public HttpResponse serve(ServiceRequestContext ctx, HttpRequest req) {
        if (req.method() != HttpMethod.POST) {
            return HttpResponse.of(
                    ResponseHeaders.builder(HttpStatus.METHOD_NOT_ALLOWED)
                            .contentType(MediaType.JSON_UTF_8)
                            .set(HttpHeaderNames.ALLOW, HttpMethod.POST.name())
                            .build(),
                    HttpData.ofUtf8("{\"error\":\"method not allowed\"}"));
        }

        GatewayAuthResult auth = authenticator.check(req.headers(), ctx.path());
        if (auth.rejected()) {
            return auth.rejection();
        }

        return HttpResponse.of(req.aggregate().thenApply(request ->
                dispatch(ctx, request, auth.principal())));
    }

    private HttpResponse dispatch(ServiceRequestContext ctx,
                                  AggregatedHttpRequest request,
                                  GatewayPrincipal principal) {
        final JsonNode parsed;
        try {
            parsed = MAPPER.readTree(request.contentUtf8());
        } catch (Exception e) {
            return badRequest("request body must be a JSON object");
        }
        if (!(parsed instanceof ObjectNode objectNode)) {
            return badRequest("request body must be a JSON object");
        }

        JsonNode strategyNode = objectNode.get("strategy");
        if (strategyNode != null && !strategyNode.isTextual()) {
            return badRequest("strategy must be a string");
        }
        String strategy = strategyNode == null
                ? DEFAULT_STRATEGY
                : strategyNode.textValue().trim().toLowerCase(Locale.ROOT);
        String routeName = STRATEGY_ROUTES.get(strategy);
        if (routeName == null) {
            return badRequest("unsupported strategy; expected one of embedding, model, online, sequential");
        }

        MicroserviceRoute route = routesByName.get(routeName);
        if (route == null) {
            return GatewayProxyService.gatewayError(
                    HttpStatus.SERVICE_UNAVAILABLE, routeName + " route unavailable");
        }

        ObjectNode rewrittenBody = objectNode.deepCopy();
        rewrittenBody.remove("strategy");
        final byte[] content;
        try {
            content = MAPPER.writeValueAsBytes(rewrittenBody);
        } catch (Exception e) {
            return badRequest("request body must be a JSON object");
        }
        AggregatedHttpRequest rewritten = AggregatedHttpRequest.of(
                request.headers(), HttpData.wrap(content));
        String targetPath = "/v2/recommend"
                + (ctx.query() == null || ctx.query().isBlank() ? "" : "?" + ctx.query());
        return forwarder.forward(ctx, rewritten, route, targetPath, principal);
    }

    private static HttpResponse badRequest(String message) {
        return GatewayProxyService.gatewayError(HttpStatus.BAD_REQUEST, message);
    }
}
