# Local ElastiCache stand-in

A throwaway local `redis-server` used to **run** the eviction invariant that EKS relies on
ElastiCache to enforce, with no AWS account. Same intent as the
[local CDN stand-in](cdn-local.md): the production resource is out-of-band, so the part of
it that carries a correctness claim gets exercised locally instead of only asserted in prose.

For the design, see [Caching §8](../system_design/02_Caching.md#8-redis-itself-as-a-cache-tier).

## Run it

```bash
./scripts/simulate-elasticache-eviction.sh                      # defaults: :6399, 8mb
./scripts/simulate-elasticache-eviction.sh --maxmemory 32mb     # more headroom
```

Needs `redis-server` and `redis-cli` on `PATH` (`brew install redis`) — **no Docker**. It
starts its own instance on a spare port, runs four scenarios, and exits non-zero if the
shipped configuration does not behave as documented.

## What it shows

Measured at `maxmemory=8mb` with 51 authoritative keys (`shard:topology` + 50 seeded
embeddings) and ~3000 filler writes of 4 KB each:

| Scenario | Evicted | Refused | Authoritative kept | Used MB |
|---|---|---|---|---|
| 1. `volatile-lru`, TTL'd pressure | 1579 | 0 | **51/51** | 8.0 |
| 2. `allkeys-lru`, TTL'd pressure | ~1600 | 0 | **19/51** (varies) | 8.0 |
| 3. `volatile-lru`, un-TTL'd pressure | 0 | 1565 | 51/51 | 8.1 |
| 4. `volatile-lru`, un-TTL'd **via one Lua script** | 0 | n/a | 51/51 | **15.8** |

1. **The shipped config.** Eviction happens, and every key without a TTL survives it.
2. **What shipped before.** The same pressure destroys authoritative state. This is
   **probabilistic** — Redis samples for approximate LRU, so results move run to run. Across
   5 trials `shard:topology` itself was evicted in **4**, with embedding survival ranging
   from 0/50 to 24/50. "Usually destroyed, sometimes lucky" is the honest characterization;
   the fix removes the coin flip rather than improving the odds.
3. **The trade.** With nothing evictable, Redis refuses writes (`OOM command not allowed…`)
   instead of dropping state — [sharp edge 6](../system_design/02_Caching.md#sharp-edges--notes).
   Loud beats silent for authoritative data, but headroom becomes something to watch:
   `redis_cache_used_memory_bytes` against `redis_cache_max_memory_bytes`.
4. **A caveat no policy fixes.** `maxmemory` is enforced when a *command is dispatched*, not
   per `redis.call` inside a script, so a single script runs to completion and overshoots —
   here to 15.8 MB against an 8 MB limit, near 2×. The mechanism is real, but the magnitude
   here is not: the sinks write 5 keys (`SET_IF_NEWER_WITH_LINEAGE_SCRIPT`) or `top-k`
   members, default 10 (`ATOMIC_TOPK_SCRIPT`), per invocation. The 15.8 MB above comes from
   writing 3000 keys in a single `EVAL`, which is a synthetic worst case, not sink behavior.
   Worth knowing before adding a batching writer.

## Rehearsing the parameter-group script

[`scripts/set-elasticache-parameters.sh`](../../scripts/set-elasticache-parameters.sh) talks
to the ElastiCache **control plane**, which a local Redis does not provide. Two ways to
exercise it without an AWS account:

```bash
# Its logic, against a fake `aws` on PATH — this is what CI-equivalent coverage looks like.
./scripts/test-set-elasticache-parameters.sh

# Against a simulator, if you run one. AWS CLI v2 honors AWS_ENDPOINT_URL natively,
# so the script needs no flag for this (ElastiCache is a LocalStack Pro API).
AWS_ENDPOINT_URL=http://localhost:4566 PARAMETER_GROUP=recsys-redis7 \
  ./scripts/set-elasticache-parameters.sh verify
```

## What this does *not* simulate

Everything here is Redis data-plane semantics. Untouched by the local stand-in:

- **Parameter groups** — the control plane the operational script drives. The invariant is
  *demonstrated* locally and *applied* to real ElastiCache.
- **Multi-AZ automatic failover** and the ~30 s primary DNS flip — see
  [zonal resilience](zonal-resilience.md).
- **Global Datastore** cross-region replication, and the fact that it replicates data but
  **not** parameter groups — which is why the policy must be set once per region.
- **Reader-endpoint routing**, snapshot/restore, and IAM.

So a green run here means "the policy does what the design claims", not "production is
configured correctly". Only `redis_cache_evicts_only_volatile_keys`, reported by the running
cluster, answers the second question.
