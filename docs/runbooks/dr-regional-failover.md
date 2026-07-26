# Runbook: Regional DR Failover (us-east-1 → us-west-2)

This is an operator-controlled active/passive failover. The capacity script may
change only the four `autoscaling/v2` HPAs in namespace `recsys`; it never
promotes Aurora/ElastiCache, changes DNS, or switches traffic.

The environment must already have the west EKS warm standby, cross-region ECR
replication, Aurora Global Database, ElastiCache Global Datastore, and the
reviewed Route53/origin failover records. Keep the standby deployed at the same
immutable release as the primary. After the CDN rollout, the failover record is
`origin.recsys.example.com` behind the CloudFront public alias; before it, the
record is the public API hostname. Keep `recsys-gateway-origin-secret` created
and rotated in both contexts per [cdn-operations.md](cdn-operations.md); a
missing standby secret disables origin enforcement instead of failing startup.

## Preconditions and evidence

Before any cluster-facing command:

1. Pin the same approved immutable image digest in the primary, standby, and
   active-standby overlays. The checked-in placeholder digest intentionally
   makes the command fail closed.
2. Select the exact standby context and make it current. The requested context,
   region, current context, and authoritative HTTPS cluster endpoint must all
   agree.
3. Create a protected context identity file:

   ```json
   {
     "contexts": {
       "prod-us-west-2": {
         "region": "us-west-2",
         "server": "https://authoritative-eks-endpoint.example"
       }
     }
   }
   ```

4. Produce fresh dependency evidence using approved read-only probes. It is
   valid for 15 minutes and must contain the canonical HPA manifest digest used
   by this operation:

   ```json
   {
     "schemaVersion": 1,
     "source": "recsys-dependency-observer/v1",
     "provenance": "approved-read-only-dependency-probes",
     "observedAt": "2026-07-26T12:00:00Z",
     "status": "healthy",
     "manifestDigests": ["<canonical-hpa-manifest-digest>"]
   }
   ```

   ```bash
   export DR_CONTEXT_IDENTITY_FILE=/secure/change/context-identity.json
   export DR_DEPENDENCY_EVIDENCE_FILE=/secure/change/dependencies.json
   ```

The script also validates exact workload/HPA/PDB sets, image identity,
topology constraints, current/live HPA min and max values, rollout and ready
replicas, cross-zone pod placement, service readiness endpoints, and dependency
evidence. Unknown, partial, contradictory, stale, or changing state fails
closed.

## Prepare standby application capacity

Use a new report path for every invocation:

```bash
scripts/dr-standby-capacity.sh promote \
  --context prod-us-west-2 \
  --region us-west-2 \
  --report artifacts/dr-promote-<change-id>.json
```

`promote` sends only the canonical four-HPA JSON payload to Kubernetes. It
converges the live min/max values to the active overlay, waits for rollout and
readiness, and rechecks exact HPA state at the final gate. Re-running against
already-converged capacity records `capacityChange: "none"`. Reports are
schema version 1, written atomically under locks, and never overwrite an
existing path; archive both failed (`ready: false`) and successful reports.

Review `checks`, `manifestDigest`, `capacityChange`, `ready`, and
`remainingOperatorActions`. Do not proceed unless `ready` is `true`.

## Promote the data tier

The operator now performs the separately approved Aurora, ElastiCache, and
streaming promotion steps in
[dr-data-tier-promotion.md](dr-data-tier-promotion.md). The capacity script
does not perform or authorize these actions.

Afterward, create fresh operator evidence (valid for 15 minutes):

```json
{
  "source": "approved-change-record/<change-id>",
  "observedAt": "2026-07-26T12:05:00Z",
  "writerIdentity": "us-west-2",
  "replicationDirection": "west-to-east",
  "lagStatus": "accepted",
  "rpoAccepted": true,
  "trafficTarget": "us-west-2",
  "health": "healthy",
  "manifestDigest": "<canonical-hpa-manifest-digest>"
}
```

The source must be nonempty and the values must come from approved operator
observations. Do not copy illustrative timestamps or digests.

## Prove cutover prerequisites

```bash
scripts/dr-standby-capacity.sh cutover-check \
  --context prod-us-west-2 \
  --region us-west-2 \
  --evidence /secure/change/cutover-evidence.json \
  --report artifacts/dr-cutover-check-<change-id>.json
```

This command is read-only. In addition to validating the evidence, it
independently re-observes exact active HPA capacity, topology, services, and
dependencies, with the live HPA state checked again as the final gate.

Only after a `ready: true` report does the authorized operator explicitly
change the Route53/origin traffic target. CloudFront deployments use
`origin.recsys.example.com`; pre-CDN deployments use the public API hostname.
Verify the selected hostname and a real recommendation request, then archive
the promote, data-tier, cutover-check, and traffic-change evidence together.

## Safety and rollback

- Never use `kubectl apply -k` as a substitute for this capacity step.
- Do not reuse report filenames; an existing report is deliberately preserved.
- Concurrent operations for the same context/namespace serialize, including
  after wrapper termination while the real child remains alive.
- A failed check is not authority to bypass the gate. Correct the observation,
  produce fresh evidence, and use a new report path.
- If traffic has not moved, `demote` may restore only the warm HPA floor. After
  traffic has moved, follow the failback runbook before demotion.

## RTO / RPO

Record observed read RTO, write RTO, accepted RPO, and every operator action in
the change record. No fixed RPO is inferred by the script.
