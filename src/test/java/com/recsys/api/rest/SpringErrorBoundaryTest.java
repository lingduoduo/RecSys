package com.recsys.api.rest;

import com.recsys.application.experiment.ABTestService;
import com.recsys.application.model.ModelRuntimeProvider;
import com.recsys.application.recommendation.RecommendationService;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Pins what the real embedded Tomcat + Spring MVC stack returns when a controller throws a JVM
 * {@link Error}. {@code GlobalExceptionHandler}'s catch-all is {@code @ExceptionHandler(Exception.class)},
 * and {@code Error} is not an {@code Exception} — so by type it should not match. Measured: it does,
 * because {@code DispatcherServlet.doDispatch} first wraps any {@code Throwable} from the handler in a
 * {@code ServletException("Handler dispatch failed")}, and that wrapper is what reaches the resolver.
 * The client therefore sees the same {@code {"error":"internal server error"}} 500 as for a
 * RuntimeException, the cause is logged, and the JVM keeps serving (liveness stays 200 — which is
 * itself a finding, see 18_Fault_Tolerance §9). This runs against the real container on purpose:
 * MockMvc would surface the wrapped exception to the test instead of exercising the error path.
 */
@SpringBootTest(classes = {ModelApplication.class, SpringErrorBoundaryTest.ThrowingControllerConfig.class},
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class SpringErrorBoundaryTest {

    @MockBean RecommendationService recommendationService;
    @MockBean ABTestService abTestService;
    @MockBean ModelRuntimeProvider modelRuntimeProvider;
    @Autowired TestRestTemplate rest;

    @TestConfiguration
    static class ThrowingControllerConfig {
        @Bean
        ThrowingController throwingController() {
            return new ThrowingController();
        }
    }

    @RestController
    static class ThrowingController {
        @GetMapping("/boundary/throw/{kind}")
        public String throwIt(@PathVariable("kind") String kind) {
            switch (kind) {
                case "runtime": throw new IllegalStateException("boundary");
                case "assertion": throw new AssertionError("boundary");
                case "linkage": throw new NoClassDefFoundError("boundary");
                case "stackoverflow": throw new StackOverflowError("boundary");
                case "oom": throw new OutOfMemoryError("boundary");
                default: return "ok";
            }
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {"runtime", "assertion", "linkage", "stackoverflow", "oom"})
    void anErrorFromAControllerIsAnsweredLikeAnExceptionAndTheProcessStaysLive(String kind) {
        ResponseEntity<String> res = rest.getForEntity("/boundary/throw/" + kind, String.class);

        assertThat(res.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
        assertThat(res.getBody()).as("GlobalExceptionHandler's catch-all produced the body, via the ServletException wrapper")
                .contains("\"error\":\"internal server error\"");
        assertThat(rest.getForEntity("/health/live", String.class).getStatusCode())
                .as("liveness is a constant UP; an Error on a request thread never changes it")
                .isEqualTo(HttpStatus.OK);
    }
}
