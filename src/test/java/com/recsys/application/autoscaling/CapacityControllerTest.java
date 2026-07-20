package com.recsys.application.autoscaling;

import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class CapacityControllerTest {

    /** Fake actuator: mutable runningCount, clamps setDesiredCapacity into [min,max]. */
    static final class FakeActuator implements CapacityActuator {
        int running; final int min; final int max; int lastDesired = -1; int applyCount = 0;
        FakeActuator(int running, int min, int max) { this.running = running; this.min = min; this.max = max; }
        @Override public int runningCount() { return running; }
        @Override public int minSize() { return min; }
        @Override public int maxSize() { return max; }
        @Override public void setDesiredCapacity(int d) {
            lastDesired = d; applyCount++; running = Math.max(min, Math.min(max, d));
        }
    }

    private static CapacitySignalSource signal(double util, boolean surge) {
        return () -> new CapacitySignal(util, surge);
    }

    @Test void scalesOutWhenOverTarget() {
        FakeActuator a = new FakeActuator(5, 1, 20);
        CapacityController c = new CapacityController(a, signal(1.40, false),
                new CapacityScalingPolicy(), 60_000, 300_000, () -> 0L);
        CapacityController.ScalingDecision d = c.tick();
        assertThat(d.applied()).isTrue();
        assertThat(d.desired()).isEqualTo(10);
        assertThat(d.reason()).isEqualTo("scaled-out");
        assertThat(a.lastDesired).isEqualTo(10);
    }

    @Test void steadyDoesNotApply() {
        FakeActuator a = new FakeActuator(4, 1, 20);
        CapacityController c = new CapacityController(a, signal(0.70, false),
                new CapacityScalingPolicy(), 60_000, 300_000, () -> 0L);
        CapacityController.ScalingDecision d = c.tick();
        assertThat(d.applied()).isFalse();
        assertThat(d.reason()).isEqualTo("steady");
        assertThat(a.applyCount).isZero();
    }

    @Test void scaleOutCooldownGatesUntilElapsed() {
        FakeActuator a = new FakeActuator(5, 1, 50);
        AtomicLong clock = new AtomicLong(0);
        CapacityController c = new CapacityController(a, signal(1.40, false),
                new CapacityScalingPolicy(), 60_000, 300_000, clock::get);
        assertThat(c.tick().applied()).isTrue();          // t=0: 5 -> 10
        clock.set(30_000);
        CapacityController.ScalingDecision gated = c.tick(); // within out-cooldown
        assertThat(gated.applied()).isFalse();
        assertThat(gated.reason()).isEqualTo("cooldown");
        clock.set(70_000);
        assertThat(c.tick().applied()).isTrue();          // past out-cooldown
    }

    @Test void scaleInUsesLongerCooldown() {
        FakeActuator a = new FakeActuator(10, 1, 50);
        AtomicLong clock = new AtomicLong(0);
        CapacityController c = new CapacityController(a, signal(0.10, false),
                new CapacityScalingPolicy(), 60_000, 300_000, clock::get);
        CapacityController.ScalingDecision first = c.tick(); // t=0: 10 -> ceil(10*.1/.7)=2
        assertThat(first.applied()).isTrue();
        assertThat(first.reason()).isEqualTo("scaled-in");
        clock.set(120_000);                                  // past OUT cooldown, within IN cooldown
        CapacityController.ScalingDecision gated = c.tick();  // 2 -> ceil(2*.1/.7)=1, gated
        assertThat(gated.applied()).isFalse();
        assertThat(gated.reason()).isEqualTo("cooldown");
    }

    @Test void surgeScalesOutByStep() {
        FakeActuator a = new FakeActuator(3, 1, 50);
        CapacityController c = new CapacityController(a, signal(0.10, true),
                new CapacityScalingPolicy(), 60_000, 300_000, () -> 0L);
        CapacityController.ScalingDecision d = c.tick();
        assertThat(d.applied()).isTrue();
        assertThat(d.desired()).isEqualTo(5);               // max(1, 3+2)
    }
}
