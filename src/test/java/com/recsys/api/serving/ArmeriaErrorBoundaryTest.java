package com.recsys.api.serving;

import com.linecorp.armeria.client.WebClient;
import com.linecorp.armeria.common.AggregatedHttpResponse;
import com.linecorp.armeria.common.HttpResponse;
import com.linecorp.armeria.common.HttpStatus;
import com.linecorp.armeria.common.MediaType;
import com.linecorp.armeria.server.Server;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.TestInstance;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what Armeria does with a JVM {@link Error} escaping a service, because every 6010/7010/8010
 * request handler is written as if only {@code Exception} can escape: the
 * {@code catch (Exception e)} in {@code BaseApiService}, {@code CatalogService},
 * {@code RecommendationService} and friends does not match an {@code Error}, so an
 * {@code OutOfMemoryError} or {@code StackOverflowError} raised while scoring leaves the
 * handler and lands in the framework. Measured (Armeria 1.28): the framework answers 500 and
 * the server keeps serving, for direct {@code serve()} throws and for the
 * {@code HttpResponse.of(CompletableFuture.supplyAsync(...))} shape alike — Armeria does not
 * rethrow "fatal" errors and does not leave the request hanging. 18_Fault_Tolerance §9 relies on
 * exactly this; an Armeria upgrade that changed it would fail here rather than in production.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class ArmeriaErrorBoundaryTest {

    private Server server;
    private WebClient client;

    private static Throwable throwableOf(String kind) {
        return switch (kind) {
            case "runtime" -> new IllegalStateException("boundary");
            case "assertion" -> new AssertionError("boundary");
            case "linkage" -> new NoClassDefFoundError("boundary");
            case "stackoverflow" -> new StackOverflowError("boundary");
            case "oom" -> new OutOfMemoryError("boundary");
            default -> throw new IllegalArgumentException(kind);
        };
    }

    @SuppressWarnings("unchecked")
    private static <T, E extends Throwable> T sneakyThrow(Throwable t) throws E {
        throw (E) t;
    }

    @BeforeAll
    void start() {
        var builder = Server.builder().http(0).requestTimeout(Duration.ofSeconds(3));
        for (String kind : new String[]{"runtime", "assertion", "linkage", "stackoverflow", "oom"}) {
            builder.service("/direct/" + kind, (ctx, req) -> sneakyThrow(throwableOf(kind)));
            // The production shape: the handler body runs on the common pool and only Exception is caught.
            builder.service("/async/" + kind, (ctx, req) -> HttpResponse.of(
                    CompletableFuture.supplyAsync(() -> {
                        try {
                            return ArmeriaErrorBoundaryTest.<HttpResponse, RuntimeException>sneakyThrow(throwableOf(kind));
                        } catch (Exception e) {
                            return HttpResponse.of(HttpStatus.INTERNAL_SERVER_ERROR, MediaType.PLAIN_TEXT_UTF_8, "caught-as-exception");
                        }
                    })));
        }
        server = builder.build();
        server.start().join();
        client = WebClient.builder("http://127.0.0.1:" + server.activeLocalPort())
                .responseTimeout(Duration.ofSeconds(5)).build();
    }

    @AfterAll
    void stop() {
        server.stop().join();
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "assertion", "linkage", "stackoverflow", "oom"})
    void anErrorEscapingServeBecomesA500AndTheServerSurvives(String kind) {
        AggregatedHttpResponse res = client.get("/direct/" + kind).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(server.activePort()).as("server still listening after %s", kind).isNotNull();
    }

    @ParameterizedTest
    @ValueSource(strings = {"assertion", "linkage", "stackoverflow", "oom"})
    void anErrorInsideTheAsyncHandlerBypassesCatchExceptionButStillBecomesA500(String kind) {
        AggregatedHttpResponse res = client.get("/async/" + kind).aggregate().join();

        assertThat(res.status()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.contentUtf8()).as("the handler's catch (Exception) did not see the Error").doesNotContain("caught-as-exception");
        assertThat(client.get("/async/runtime").aggregate().join().contentUtf8())
                .as("server still serving; and an Exception on the same path IS caught by the handler")
                .isEqualTo("caught-as-exception");
    }
}
