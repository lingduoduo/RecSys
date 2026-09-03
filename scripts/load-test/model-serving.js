import http from 'k6/http';
import { check, fail } from 'k6';
import { Rate } from 'k6/metrics';

// Run: k6 run --env BASE_URL=http://localhost:8080 scripts/load-test/model-serving.js
//
// Two scenarios, deliberately separate, so cache throughput never contaminates an inference
// capacity number:
//
//   onnx_inference  — known users (the bundled feature config encodes 123..127 into distinct
//                     user-tower indices; anything else collapses to __UNK__ and the shared
//                     cold-start pool) with a random exclusion subset and k per request, so the
//                     response cache cannot satisfy the measured path.
//   cache_behavior  — one stable request repeated, so it measures the cache and only the cache.
//
// setup() reads recsys_model_onnx_runs_total from /actuator/prometheus and teardown() reads it
// again: the run FAILS unless the counter increased. A green run therefore proves that ONNX
// sessions actually executed, rather than that something answered quickly.
//
// Notes:
//  - RECSYS_SUBMIT_TOKEN_ENABLED must be false (the default) or every request gets 409.
//  - METRICS_URL overrides where the Prometheus exposition is fetched from (default: BASE_URL).
const BASE_URL = __ENV.BASE_URL || 'http://localhost:8080';
const METRICS_URL = __ENV.METRICS_URL || `${BASE_URL}/actuator/prometheus`;
const ONNX_RUNS_METRIC = 'recsys_model_onnx_runs_total';

const KNOWN_USER_IDS = ['123', '124', '125', '126', '127'];
const ITEM_IDS = ['1', '2', '3', '4', '5', '6', '7', '8', '9', '10', '11', '12'];

const errorRate = new Rate('errors');

export const options = {
  scenarios: {
    onnx_inference: {
      executor: 'ramping-arrival-rate',
      exec: 'onnxInference',
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
    cache_behavior: {
      executor: 'constant-arrival-rate',
      exec: 'cacheBehavior',
      rate: 20,
      timeUnit: '1s',
      duration: '4m',
      preAllocatedVUs: 20,
      maxVUs: 40,
      startTime: '30s',   // after the inference scenario's warm-up, so both run concurrently
    },
  },
  thresholds: {
    // Per-scenario, never pooled: a cache hit at 3ms would otherwise mask a slow inference p95.
    'http_req_duration{scenario:onnx_inference}': ['p(95)<500'],
    'http_req_duration{scenario:cache_behavior}': ['p(95)<100'],
    'errors{scenario:onnx_inference}': ['rate<0.01'],
    'errors{scenario:cache_behavior}': ['rate<0.01'],
  },
};

// Mark any non-200 HTTP response as a request failure in k6's built-in http_req_failed metric.
http.setResponseCallback(http.expectedStatuses(200));

function onnxRunsTotal() {
  const res = http.get(METRICS_URL, { tags: { scenario: 'metrics' } });
  if (res.status !== 200) {
    fail(`cannot read ${METRICS_URL}: HTTP ${res.status}`);
  }
  let sum = 0;
  let seen = false;
  for (const line of res.body.split('\n')) {
    // Exposition line: `recsys_model_onnx_runs_total{variant="training",...} 1234.0`
    if (line.startsWith(ONNX_RUNS_METRIC) && !line.startsWith('#')) {
      const value = parseFloat(line.substring(line.lastIndexOf(' ') + 1));
      if (!Number.isNaN(value)) {
        sum += value;
        seen = true;
      }
    }
  }
  if (!seen) {
    fail(`${ONNX_RUNS_METRIC} is absent from ${METRICS_URL}: is this the model service, and has a runtime loaded?`);
  }
  return sum;
}

export function setup() {
  const onnxRunsBefore = onnxRunsTotal();
  console.log(`[setup] ${ONNX_RUNS_METRIC} = ${onnxRunsBefore}`);
  return { onnxRunsBefore };
}

export function teardown(data) {
  const onnxRunsAfter = onnxRunsTotal();
  const delta = onnxRunsAfter - data.onnxRunsBefore;
  console.log(`[teardown] ${ONNX_RUNS_METRIC} = ${onnxRunsAfter} (delta ${delta})`);
  if (!(delta > 0)) {
    fail(`ONNX run counter did not increase during the run (before=${data.onnxRunsBefore}, after=${onnxRunsAfter}); `
      + 'the onnx_inference scenario was served by caches or fallback ranking, not by the model');
  }
}

function post(payload, scenario) {
  const res = http.post(`${BASE_URL}/api/v1/recommend`, JSON.stringify(payload), {
    headers: { 'Content-Type': 'application/json' },
    timeout: '5s',
    tags: { scenario },
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
  }, { scenario });
  errorRate.add(ok ? 0 : 1, { scenario });
}

// A random exclusion subset (2^12 possibilities) times five users times twenty k values gives
// ~400k distinct cache keys — repeats within a fifteen-minute run are negligible.
function randomExclusions() {
  const excluded = [];
  for (const itemId of ITEM_IDS) {
    if (Math.random() < 0.5) excluded.push(itemId);
  }
  return excluded;
}

export function onnxInference() {
  const userId = KNOWN_USER_IDS[Math.floor(Math.random() * KNOWN_USER_IDS.length)];
  const k = 1 + Math.floor(Math.random() * 20);
  post({ userId, k, excludeItemIds: randomExclusions() }, 'onnx_inference');
}

export function cacheBehavior() {
  post({ userId: '123', k: 10 }, 'cache_behavior');
}

function p95(metrics, name) {
  const m = metrics[name];
  return m && typeof m.values['p(95)'] === 'number' ? `${m.values['p(95)'].toFixed(0)} ms` : 'N/A';
}

function ratePct(metrics, name) {
  const m = metrics[name];
  return m ? `${(m.values.rate * 100).toFixed(2)}%` : 'N/A';
}

export function handleSummary(data) {
  console.log('\n=== model-serving load test summary ===');
  console.log(`  onnx_inference  P95 : ${p95(data.metrics, 'http_req_duration{scenario:onnx_inference}')}  (threshold: 500 ms)`);
  console.log(`  onnx_inference  err : ${ratePct(data.metrics, 'errors{scenario:onnx_inference}')}  (threshold: 1%)`);
  console.log(`  cache_behavior  P95 : ${p95(data.metrics, 'http_req_duration{scenario:cache_behavior}')}  (threshold: 100 ms)`);
  console.log(`  cache_behavior  err : ${ratePct(data.metrics, 'errors{scenario:cache_behavior}')}  (threshold: 1%)`);
  console.log('  ONNX run delta      : see the [teardown] line above — the run fails if it is not positive');
  return {
    'summary.json': JSON.stringify(data),
  };
}
