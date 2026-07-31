package com.recsys.infrastructure.observability;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;
import java.net.ConnectException;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.channels.UnresolvedAddressException;
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

    /**
     * The result of one batch POST.
     *
     * <p>{@link #INDETERMINATE} is deliberately distinct from {@link #TRANSPORT_FAILURE}. Once
     * the request has been written, a timeout or an interrupt tells us the <em>response</em>
     * never arrived — not that Splunk rejected the batch. Splunk may well have indexed it. The
     * two cases carry opposite risks: {@code TRANSPORT_FAILURE} means those events are lost,
     * {@code INDETERMINATE} means they may be lost <em>or</em> duplicated if anything ever
     * retries. Collapsing them into one counter reports a definite loss that may not have
     * happened, and hides the duplicate risk entirely.
     */
    enum Outcome {
        /** 2xx. Splunk accepted the batch. */
        SUCCESS,
        /** 401/403. The token is wrong, revoked, or lacks the index. Events are lost. */
        AUTH_REJECTED,
        /** Any other non-2xx. Splunk answered and refused. Events are lost. */
        SERVER_ERROR,
        /** Never reached Splunk — connection refused, DNS failure. Events are lost. */
        TRANSPORT_FAILURE,
        /** Request sent, response never seen. Delivery genuinely unknown. */
        INDETERMINATE
    }

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
            recordFailureDetail("HTTP " + status + ": " + sanitize(response.body()));
            if (status == 401 || status == 403) return Outcome.AUTH_REJECTED;
            return Outcome.SERVER_ERROR;
        } catch (InterruptedException e) {
            // Shutdown in progress. Restore the flag so the drain loop sees it and exits.
            // The request was already handed to the client, so Splunk may or may not have
            // taken it — that is INDETERMINATE, not a known loss.
            Thread.currentThread().interrupt();
            recordFailureDetail("interrupted while awaiting the response; delivery unknown");
            return Outcome.INDETERMINATE;
        } catch (HttpTimeoutException e) {
            // The request went out; the response did not come back in time. Splunk may have
            // indexed the batch regardless.
            recordFailureDetail("timed out after " + timeout.toMillis() + "ms awaiting the "
                    + "response; delivery unknown");
            return Outcome.INDETERMINATE;
        } catch (ConnectException | UnresolvedAddressException | UnknownHostException e) {
            // Never left the host: nothing was delivered, so this is a definite loss.
            recordFailureDetail(sanitize(e.getClass().getSimpleName() + ": " + e.getMessage()));
            return Outcome.TRANSPORT_FAILURE;
        } catch (Exception e) {
            // Anything else — a TLS failure, a truncated response, an IO error mid-exchange.
            // We cannot tell whether the request was written, so do not claim a definite loss.
            recordFailureDetail(sanitize(e.getClass().getSimpleName() + ": " + e.getMessage())
                    + " (delivery unknown)");
            return Outcome.INDETERMINATE;
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

    private static final int MAX_DETAIL_CHARS = 300;

    /**
     * Bounds and scrubs anything that reaches a log line.
     *
     * <p>A genuine HEC rejection body is a few dozen bytes of JSON. But this string is whatever
     * answered on the configured URL, and that is not guaranteed to be Splunk — point
     * {@code SPLUNK_HEC_URL} at the wrong host and it could be an HTML error page, a proxy's
     * diagnostic dump echoing the request headers (including our {@code Authorization: Splunk
     * <token>}), or megabytes of anything. So:
     *
     * <ul>
     *   <li>the HEC token is redacted wherever it appears, so a header-echoing proxy cannot
     *       leak the credential into logs that ship to Splunk and sit in {@code logs/*.log};
     *   <li>anything that looks like an {@code Authorization} header is redacted too, covering
     *       a token that differs from ours (a stale one, or another service's);
     *   <li>control characters are stripped, so a hostile body cannot forge extra log lines;
     *   <li>the result is truncated, so one bad response cannot dump megabytes into the log.
     * </ul>
     */
    private String sanitize(String raw) {
        if (raw == null || raw.isBlank()) return "<empty response body>";
        // Strip CR/LF and other control characters first: a body containing a newline could
        // otherwise fake a second log entry in a file-based collector.
        String collapsed = raw.replaceAll("[\\p{Cntrl}\\s]+", " ").strip();
        if (token != null && !token.isBlank()) {
            collapsed = collapsed.replace(token, "<redacted-token>");
        }
        collapsed = AUTH_HEADER.matcher(collapsed).replaceAll("$1 <redacted>");
        if (collapsed.isBlank()) return "<empty response body>";
        return collapsed.length() <= MAX_DETAIL_CHARS
                ? collapsed
                : collapsed.substring(0, MAX_DETAIL_CHARS) + "...<truncated>";
    }

    /** Matches `Authorization: Splunk xxx`, `Authorization=Bearer xxx`, and similar. */
    private static final java.util.regex.Pattern AUTH_HEADER = java.util.regex.Pattern.compile(
            "(?i)(authorization\\s*[:=]?\\s*(?:splunk|bearer|basic)?)\\s*[A-Za-z0-9._~+/=-]{8,}");

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
