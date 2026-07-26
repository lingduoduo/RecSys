# Runbook: DR Failback (us-west-2 → us-east-1)

Failback is a planned, operator-controlled change. The DR script proves
application prerequisites and may change only standby HPAs; it never changes
data roles, DNS, or traffic.

## 1. Re-establish and observe the east data tier

- Rebuild Aurora and ElastiCache replication toward us-east-1.
- Let the east region catch up and record writer identity, replication
  direction, lag/RPO acceptance, health, and the intended traffic target.
- Pin the same approved immutable image digest in all primary/standby overlays
  and deploy/warm us-east-1 through the normal reviewed deployment process.

Do not proceed while writer identity, direction, lag acceptance, image identity,
or health is ambiguous.

## 2. Prepare the evidence files

Set the same strict trust inputs described in
[dr-regional-failover.md](dr-regional-failover.md):

```bash
export DR_CONTEXT_IDENTITY_FILE=/secure/change/context-identity.json
export DR_DEPENDENCY_EVIDENCE_FILE=/secure/change/dependencies.json
```

The context file must bind `prod-us-west-2` to region `us-west-2` and its exact
authoritative HTTPS endpoint. Dependency evidence must be schema-v1, healthy,
from `recsys-dependency-observer/v1` /
`approved-read-only-dependency-probes`, no more than 15 minutes old, and include
the canonical HPA manifest digest.

Create fresh failback evidence:

```json
{
  "source": "approved-change-record/<change-id>",
  "observedAt": "2026-07-26T13:00:00Z",
  "writerIdentity": "us-east-1",
  "replicationDirection": "east-to-west",
  "lagStatus": "accepted",
  "rpoAccepted": true,
  "trafficTarget": "us-east-1",
  "health": "healthy",
  "manifestDigest": "<canonical-hpa-manifest-digest>"
}
```

## 3. Prove failback prerequisites

While us-west-2 still has active application capacity, run:

```bash
scripts/dr-standby-capacity.sh failback-check \
  --context prod-us-west-2 \
  --region us-west-2 \
  --evidence /secure/change/failback-evidence.json \
  --report artifacts/dr-failback-check-<change-id>.json
```

`failback-check` is read-only. It requires the exact active HPA min/max values,
fresh evidence, cross-zone ready pods, healthy PDBs/services/dependencies, and
re-observes active capacity as the final gate. Review the schema-v1 report and
continue only when `ready` is `true`.

## 4. Perform explicit data and traffic changes

The authorized operator performs the approved data-tier role change and
explicitly selects the us-east-1 traffic target. Verify:

- the intended east writer and east-to-west replication are observed;
- the Route53/origin record selects us-east-1;
- health and a real recommendation request succeed through the public path;
- us-west-2 receives no production traffic.

The script does not perform or authorize these changes.

## 5. Restore the west warm floor

Only after traffic has left us-west-2:

```bash
scripts/dr-standby-capacity.sh demote \
  --context prod-us-west-2 \
  --region us-west-2 \
  --report artifacts/dr-demote-<change-id>.json
```

`demote` applies only the four standby HPA objects and validates rollout,
replicas, PDBs, topology, service readiness, dependencies, and final exact HPA
state. It is idempotent (`capacityChange: "none"` when already converged).

Reports are atomic, locked, and never overwritten. Use a new path for every
attempt and archive the failback-check, data/traffic change, and demote reports
together, including any `ready: false` attempt.
