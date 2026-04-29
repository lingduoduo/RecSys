package com.recsys.modelbased.model.controller;

import com.recsys.modelbased.model.config.HealthProperties;
import com.recsys.modelbased.model.service.InferenceMetricsService;
import com.recsys.modelbased.model.service.UserTowerInferenceService;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/health")
public class HealthController {

    private final UserTowerInferenceService inferenceService;
    private final InferenceMetricsService metricsService;
    private final HealthProperties props;

    public HealthController(UserTowerInferenceService inferenceService,
                            InferenceMetricsService metricsService,
                            HealthProperties props) {
        this.inferenceService = inferenceService;
        this.metricsService = metricsService;
        this.props = props;
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

    // Readiness: is this instance healthy enough to receive load-balancer traffic?
    // Returns 503 to pull the instance from rotation without restarting it.
    @GetMapping("/ready")
    public ResponseEntity<Map<String, Object>> readiness() {
        if (!inferenceService.isReady()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("status", "DOWN", "reason", "model not loaded"));
        }

        InferenceMetricsService.Snapshot snap = metricsService.snapshot();

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
                "throughputPerSecond", snap.throughputPerSecond()
        ));
    }
}
