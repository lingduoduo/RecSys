package com.recsys.loadshed;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.ApplicationListener;
import org.springframework.context.event.ContextClosedEvent;
import org.springframework.core.Ordered;
import org.springframework.lang.NonNull;
import org.springframework.stereotype.Component;

/**
 * Marks the load shedder as shutting down as soon as the Spring context begins closing
 * (i.e., on SIGTERM). This makes /health/ready return 503 immediately so load balancers
 * stop routing new requests before Spring shuts down Tomcat and interrupts in-flight work.
 *
 * Spring Boot's server.shutdown=graceful then keeps Tomcat alive until existing in-flight
 * requests drain or the timeout-per-shutdown-phase elapses.
 */
@Component
public class GracefulShutdownSupport implements ApplicationListener<ContextClosedEvent>, Ordered {

    private static final Logger log = LoggerFactory.getLogger(GracefulShutdownSupport.class);

    private final LoadShedder loadShedder;

    public GracefulShutdownSupport(LoadShedder loadShedder) {
        this.loadShedder = loadShedder;
    }

    @Override
    public void onApplicationEvent(@NonNull ContextClosedEvent event) {
        log.info("SIGTERM received — marking instance as shutting down, draining load balancer traffic");
        loadShedder.markShuttingDown();
    }

    @Override
    public int getOrder() {
        // Run before any other shutdown listener so the readiness probe flips to 503
        // before beans start closing.
        return Ordered.HIGHEST_PRECEDENCE;
    }
}
