package com.recsys.modelbased.controller;

import com.recsys.modelbased.config.HealthProperties;
import com.recsys.modelbased.config.ABTestConfig;
import com.recsys.modelbased.service.GcEventTracker;
import com.recsys.modelbased.service.InferenceMetricsService;
import com.recsys.modelbased.service.JvmMemoryMonitor;
import com.recsys.modelbased.service.LoadShedder;
import com.recsys.modelbased.service.ModelRuntimeProvider;
import com.recsys.modelbased.service.RecommendationService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final ModelRuntimeProvider modelRuntimeProvider;
    private final InferenceMetricsService metricsService;
    private final JvmMemoryMonitor jvmMemoryMonitor;
    private final GcEventTracker gcEventTracker;
    private final LoadShedder loadShedder;
    private final RecommendationService recommendationService;
    private final HealthProperties props;
    private final ABTestConfig abTestConfig;

    public HealthController(ModelRuntimeProvider modelRuntimeProvider,
                            InferenceMetricsService metricsService,
                            JvmMemoryMonitor jvmMemoryMonitor,
                            GcEventTracker gcEventTracker,
                            LoadShedder loadShedder,
                            RecommendationService recommendationService,
                            HealthProperties props,
                            ABTestConfig abTestConfig) {
        this.modelRuntimeProvider = modelRuntimeProvider;
        this.metricsService = metricsService;
        this.jvmMemoryMonitor = jvmMemoryMonitor;
        this.gcEventTracker = gcEventTracker;
        this.loadShedder = loadShedder;
        this.recommendationService = recommendationService;
        this.props = props;
        this.abTestConfig = abTestConfig;
    }

    @GetMapping("/jvm")
    public JvmMemoryMonitor.Snapshot jvmMemory() {
        return jvmMemoryMonitor.snapshot();
    }

    @GetMapping("/gc")
    public GcEventTracker.Snapshot gcEvents() {
        return gcEventTracker.snapshot();
    }

    // Liveness: is the JVM/process alive? Restart container if this fails.
    @GetMapping("/live")
    public ResponseEntity<Map<String, String>> liveness() {
        return ResponseEntity.ok(Map.of("status", "UP"));
    }

    @GetMapping("/metrics")
    public InferenceMetricsService.Snapshot metrics() {
        return metricsService.snapshot();
    }

    @GetMapping("/load")
    public LoadShedder.Snapshot load() {
        return loadShedder.snapshot();
    }

    @GetMapping("/cache")
    public RecommendationService.CacheSnapshot cache() {
        return recommendationService.cacheSnapshot();
    }

    @GetMapping("/ab-tests")
    public InferenceMetricsService.ABTestSnapshot abTestMetrics() {
        return metricsService.abTestSnapshot(abTestConfig.getDefaultVariant());
    }

    // Readiness: is this instance healthy enough to receive load-balancer traffic?
    // Returns 503 to pull the instance from rotation without restarting it.
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        if (!modelRuntimeProvider.areVariantsReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "reason", "model not loaded"));
        }

        InferenceMetricsService.Snapshot snap = metricsService.snapshot();
        LoadShedder.Snapshot load = loadShedder.snapshot();

        // Explicit shutdown check: return 503 immediately on SIGTERM so load balancers
        // drain this instance before Spring closes Tomcat and interrupts in-flight requests.
        if (load.shuttingDown()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "status", "DOWN",
                            "reason", "shutting down",
                            "inFlightRequests", load.inFlightRequests()
                    ));
        }

        if (load.utilization() >= props.getMaxInFlightUtilization()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of(
                            "status", "DOWN",
                            "reason", "overloaded",
                            "inFlightRequests", load.inFlightRequests(),
                            "maxConcurrentRequests", load.maxConcurrentRequests(),
                            "utilization", load.utilization(),
                            "threshold", props.getMaxInFlightUtilization(),
                            "suggestedWeight", 0
                    ));
        }

        if (snap.recentRequests() >= props.getMinSampleSize()) {
            if (snap.recentFailureRate() > props.getMaxFailureRate()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of(
                                "status", "DOWN",
                                "reason", "high failure rate",
                                "recentFailureRate", snap.recentFailureRate(),
                                "threshold", props.getMaxFailureRate()
                        ));
            }
            if (snap.recentAvgLatencyMs() > props.getMaxAvgLatencyMs()) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of(
                                "status", "DOWN",
                                "reason", "high inference latency",
                                "recentAvgLatencyMs", snap.recentAvgLatencyMs(),
                                "thresholdMs", props.getMaxAvgLatencyMs()
                        ));
            }
        }

        return ResponseEntity.ok(Map.of(
                "status", "UP",
                "recentRequests", snap.recentRequests(),
                "recentFailureRate", snap.recentFailureRate(),
                "recentAvgLatencyMs", snap.recentAvgLatencyMs(),
                "throughputPerSecond", snap.throughputPerSecond(),
                "inFlightRequests", load.inFlightRequests(),
                "maxConcurrentRequests", load.maxConcurrentRequests(),
                "utilization", load.utilization(),
                "suggestedWeight", load.suggestedWeight()
        ));
    }
}
