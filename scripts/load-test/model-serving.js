import http from 'k6/http';
import { check } from 'k6';
import { Rate, Trend } from 'k6/metrics';

const errorRate  = new Rate('errors');
const reqLatency = new Trend('req_latency_ms', true);

// Run: k6 run --env BASE_URL=http://localhost:8080 scripts/load-test/model-serving.js
// Note: RECSYS_SUBMIT_TOKEN_ENABLED must be false (the default) — if enabled, all requests get 409.
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';

export const options = {
  scenarios: {
    staged_load: {
      executor: 'ramping-arrival-rate',
      startRate: 1,
      timeUnit: '1s',
      preAllocatedVUs: 60,
      maxVUs: 150,
      stages: [
        { duration: '30s', target: 10 },   // warm up to 10 rps
        { duration: '60s', target: 50 },   // ramp to steady-state 50 rps
        { duration: '120s', target: 50 },  // hold at 50 rps (2 min)
        { duration: '30s', target: 100 },  // spike to 100 rps
        { duration: '30s', target: 50 },   // return to steady-state
        { duration: '30s', target: 0 },    // ramp down
      ],
    },
  },
  thresholds: {
    'http_req_duration{scenario:staged_load}': ['p(95)<500'],
    'errors': ['rate<0.01'],
  },
};

const CACHED_USER_IDS = ['1', '10', '50', '100', '123', '200', '300', '400', '500', '600'];

// Mark any non-200 HTTP response as a request failure in k6's built-in http_req_failed metric.
http.setResponseCallback(http.expectedStatuses(200));

export default function () {
  // 70% of requests use unique IDs to exercise ONNX inference (cache miss path).
  // 30% reuse known IDs to test the cache hit path.
  const userId = Math.random() < 0.7
    ? `user-load-${__VU}-${__ITER}`
    : CACHED_USER_IDS[Math.floor(Math.random() * CACHED_USER_IDS.length)];
  const payload = JSON.stringify({ userId, k: 10 });

  const res = http.post(`${BASE_URL}/api/v1/recommend`, payload, {
    headers: { 'Content-Type': 'application/json' },
    timeout: '5s',
  });

  const ok = check(res, {
    'status 200':          (r) => r.status === 200,
    'not degraded':        (r) => r.headers['X-Served-From'] !== 'degraded-cache',
    'has recommendations': (r) => {
      if (r.status !== 200) return false;
      try {
        const body = r.json();
        return Array.isArray(body.recommendations) && body.recommendations.length > 0;
      } catch (_) {
        return false;
      }
    },
  });

  errorRate.add(ok ? 0 : 1);
  if (ok) reqLatency.add(res.timings.duration);
}

export function handleSummary(data) {
  const d   = data.metrics.http_req_duration;
  const p95 = d ? d.values['p(95)'] : 'N/A';
  const err = data.metrics.errors ? (data.metrics.errors.values.rate * 100).toFixed(2) : '0.00';
  console.log('\n=== model-serving load test summary ===');
  console.log(`  P95 latency : ${typeof p95 === 'number' ? p95.toFixed(0) : p95} ms  (threshold: 500 ms)`);
  console.log(`  Error rate  : ${err}%  (threshold: 1%)`);
  return {
    'summary.json': JSON.stringify(data),
  };
}
