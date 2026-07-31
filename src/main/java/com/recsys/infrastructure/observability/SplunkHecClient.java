package com.recsys.infrastructure.observability;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Posts a batch body to Splunk's HTTP Event Collector.
 *
 * <p><strong>{@link #send} never throws.</strong> Every failure becomes an {@link Outcome}.
 * The appender's drain thread depends on this: an escaping exception would kill the thread
 * and silently stop all log shipping for the life of the JVM.
 *
 * <p>Uses the JDK's {@link HttpClient} deliberately, but its recursion safety is narrower than
 * it looks. {@link HttpClient} logs through {@code System.Logger}, not slf4j directly — but
 * this repo pulls in {@code org.slf4j:jul-to-slf4j} via {@code spring-boot-starter-logging},
 * Spring Boot installs {@code SLF4JBridgeHandler}, and the JDK's default {@code System.Logger}
 * finder routes to {@code java.util.logging}. So the real chain is
 * {@code System.Logger -> JUL -> slf4j -> Logback -> this appender}, and the only thing
 * actually breaking that cycle is that {@code jdk.httpclient.HttpClient.log} is unset by
 * default. <strong>Never set that property on a service with this appender attached</strong>:
 * doing so makes every HTTP request this client makes log a line, which enqueues, which
 * ships, which logs — a self-sustaining amplification loop. (Logback's per-thread `guard` in
 * {@code UnsynchronizedAppenderBase} prevents a {@code StackOverflowError}, so it pins the
 * drain thread rather than crashing — arguably worse.) Do not swap in an slf4j-backed HTTP
 * client without re-checking this, and do not assume enabling JDK HttpClient request logging
 * is a safe debugging step here.
 *
 * <p>Not {@code final}: tests subclass it to fake outcomes without standing up a server.
 */
class SplunkHecClient {

    enum Outcome { SUCCESS, AUTH_REJECTED, SERVER_ERROR, TRANSPORT_FAILURE }

    private final HttpClient httpClient;
    private final URI uri;
    private final String token;
    private final Duration timeout;

    /** Written by the drain thread, read by the appender's reporting path and by tests. */
    private volatile String lastFailureDetail;

    SplunkHecClient(SplunkHecConfig config) {
        this(buildHttpClient(config), config);
    }

    SplunkHecClient(HttpClient httpClient, SplunkHecConfig config) {
        this.httpClient = httpClient;
        this.uri = config.uri();
        this.token = config.token();
        this.timeout = config.timeout();
    }

    Outcome send(String body) {
        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("Authorization", "Splunk " + token)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(body))
                    .build();

            // Read the body rather than discarding it: HEC explains every rejection in a small
            // JSON payload ("Incorrect index", "Invalid token", "Server is busy", ...). Without
            // it an operator sees only SERVER_ERROR and has nothing to act on. Responses are
            // tens of bytes, so this costs nothing.
            HttpResponse<String> response =
                    httpClient.send(request, HttpResponse.BodyHandlers.ofString());
            int status = response.statusCode();
            if (status >= 200 && status < 300) {
                lastFailureDetail = null;
                return Outcome.SUCCESS;
            }
            recordFailureDetail("HTTP " + status + ": " + truncate(response.body()));
            if (status == 401 || status == 403) return Outcome.AUTH_REJECTED;
            return Outcome.SERVER_ERROR;
        } catch (InterruptedException e) {
            // Shutdown in progress. Restore the flag so the drain loop sees it and exits.
            Thread.currentThread().interrupt();
            recordFailureDetail("interrupted during send");
            return Outcome.TRANSPORT_FAILURE;
        } catch (Exception e) {
            // Connect refused, DNS failure, timeout, TLS failure — all the same to the caller.
            recordFailureDetail(e.getClass().getSimpleName() + ": " + e.getMessage());
            return Outcome.TRANSPORT_FAILURE;
        }
    }

    /**
     * Why the most recent failed send failed, or {@code null} after a success. Best-effort
     * diagnostics for the appender's throttled warning — deliberately not a queue of every
     * failure, which would be one more unbounded buffer on the path this design keeps bounded.
     */
    String lastFailureDetail() {
        return lastFailureDetail;
    }

    private void recordFailureDetail(String detail) {
        lastFailureDetail = detail;
    }

    /** HEC error bodies are small, but a misconfigured URL can return an HTML error page. */
    private static String truncate(String body) {
        if (body == null || body.isBlank()) return "<empty response body>";
        String collapsed = body.strip().replaceAll("\\s+", " ");
        return collapsed.length() <= 300 ? collapsed : collapsed.substring(0, 300) + "...";
    }

    private static HttpClient buildHttpClient(SplunkHecConfig config) {
        HttpClient.Builder builder = HttpClient.newBuilder()
                .connectTimeout(config.timeout())
                .followRedirects(HttpClient.Redirect.NEVER);
        if (config.insecureTls() && "https".equalsIgnoreCase(config.uri().getScheme())) {
            SSLContext insecure = insecureSslContext();
            if (insecure != null) builder.sslContext(insecure);
        }
        return builder.build();
    }

    /**
     * Trust-all context for pointing a developer at a Splunk instance using its stock
     * self-signed certificate. Reachable only via SPLUNK_HEC_INSECURE_TLS=true, which
     * defaults to false and is never set in any committed manifest.
     */
    private static SSLContext insecureSslContext() {
        try {
            TrustManager[] trustAll = {
                    new X509TrustManager() {
                        @Override public void checkClientTrusted(X509Certificate[] chain, String authType) {}
                        @Override public void checkServerTrusted(X509Certificate[] chain, String authType) {}
                        @Override public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
                    }
            };
            SSLContext context = SSLContext.getInstance("TLS");
            context.init(null, trustAll, new java.security.SecureRandom());
            return context;
        } catch (Exception e) {
            return null; // Fall back to strict verification rather than failing startup.
        }
    }
}
