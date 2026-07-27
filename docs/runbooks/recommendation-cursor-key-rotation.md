# Recommendation cursor key rotation

Recommendation cursors can cross replicas, serving paths, and regions. Every
catalog (`6010`), model (`8080`), and online (`7010`) workload must therefore
use byte-for-byte identical active and previous key material. In Kubernetes,
all three Deployments read the same logical Secret entries:

- `recsys-secrets/recommendation-cursor-signing-key`
- `recsys-secrets/recommendation-cursor-previous-key`

Replicate those two entries through the deployment's secret manager into every
region. Do not generate a key independently in each cluster, and do not start a
regional failover until the key versions match.

## Safe rolling rotation

Assume `K1` is active and `K2` is the new key. Keep each key at least 32 UTF-8
bytes. A safe rotation has two rolling stages:

1. **Make every replica accept both keys without changing the signer.** Set the
   active key to `K1` and the previous key to `K2` in every region. Roll all
   catalog, model, and online Deployments. Wait for every rollout to complete
   and verify all regions are ready before continuing. Every replica still
   issues `K1`, while both old and new replicas accept `K1` and `K2`.
2. **Flip the signer only after stage 1 is global.** Set the active key to `K2`
   and the previous key to `K1` everywhere. Roll all three Deployments in every
   region and wait for every rollout. Mixed replicas remain compatible: old
   stage-1 replicas issue `K1` and accept both; new stage-2 replicas issue `K2`
   and accept both.
3. **Retire `K1` only after its last possible cursor expires.** Start the wait
   when the final stage-2 replica becomes ready. Wait at least
   `RECOMMENDATION_CURSOR_MAX_AGE_SECONDS` (base: 900 seconds), plus the normal
   rollout/clock-skew safety margin. Then remove
   `recommendation-cursor-previous-key` everywhere and perform a final rollout.

Never combine stages 1 and 2 into one rolling update. A direct **K1 only** to
**K2 active / K1 previous** rollout creates a mixed fleet in which an old
`K1`-only replica rejects cursors issued by a new `K2` replica.

## Verification and rollback

Before each stage, confirm the secret-manager version identifiers or
out-of-band digests match across regions without printing key values. During
and after each rollout, observe:

- `recsys.pagination.cursor.previous_key.verified` (Prometheus:
  `recsys_pagination_cursor_previous_key_verified_total`);
- cursor rejection counters with `reason="signature"`;
- request error rate and page/terminal-page counters.

Catalog and online serving expose these counters at `/metrics`; model serving
exposes them at `/actuator/prometheus`. A rise in signature rejections means at
least one replica, serving path, or region does not have the shared pair. Stop
the rollout and restore the last globally compatible pair. During stage 2 that
rollback pair is `K1` active and `K2` previous.

Regional failover exercises must verify a cursor issued in each region can be
continued in every other region before the old key is retired.
