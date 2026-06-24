package com.recsys.resilience;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FaultInjectorTest {

    @Test
    void noopNeverInjectsAnything() {
        long start = System.currentTimeMillis();
        FaultInjector.NOOP.maybeInject("any:point");
        assertThat(System.currentTimeMillis() - start).isLessThan(50);
    }

    @Test
    void latencyInjectionSleepsAtLeastConfiguredMs() {
        FaultInjector injector = new FaultInjector();
        injector.injectLatency("db:read", 80);
        long start = System.currentTimeMillis();
        injector.maybeInject("db:read");
        assertThat(System.currentTimeMillis() - start).isGreaterThanOrEqualTo(75);
    }

    @Test
    void exceptionInjectionThrows() {
        FaultInjector injector = new FaultInjector();
        injector.injectException("redis:get", new RuntimeException("injected failure"));
        assertThatThrownBy(() -> injector.maybeInject("redis:get"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("injected failure");
    }

    @Test
    void clearRemovesFault() {
        FaultInjector injector = new FaultInjector();
        injector.injectException("channel:x", new RuntimeException("boom"));
        injector.clear("channel:x");
        // Should not throw
        injector.maybeInject("channel:x");
    }

    @Test
    void unknownPointIsAlwaysPassThrough() {
        FaultInjector injector = new FaultInjector();
        injector.injectLatency("point:a", 100);
        long start = System.currentTimeMillis();
        injector.maybeInject("point:b");
        assertThat(System.currentTimeMillis() - start).isLessThan(50);
    }
}
