# Runbook: DR Game Day

Run this quarterly in an approved non-production mirror or maintenance window.
The objective is to exercise the same evidence and authority boundaries as a
real event without treating a successful checker as permission to mutate data
or traffic.

## Objectives

- Prove the standby can converge from its warm HPA floor to active capacity.
- Prove readiness fails closed for bad identity, image, capacity, placement,
  service, dependency, writer, replication, RPO, or traffic-target evidence.
- Exercise operator-owned data-tier and traffic procedures.
- Measure read/write RTO and record accepted RPO.
- Archive immutable schema-v1 reports and source evidence for every command.

## Preparation

1. Create a drill directory with a unique change ID. Never reuse report paths;
   reports are locked and existing files are not overwritten.
2. Pin one approved immutable image digest across primary, standby, and active
   overlays. The repository placeholder must not be used.
3. Make the exact us-west-2 drill context current and create:

   ```bash
   export DR_CONTEXT_IDENTITY_FILE=/secure/drill/context-identity.json
   export DR_DEPENDENCY_EVIDENCE_FILE=/secure/drill/dependencies.json
   ```

   The identity file binds the context to region `us-west-2` and its exact
   authoritative HTTPS endpoint. The dependency file follows the schema in
   [dr-regional-failover.md](dr-regional-failover.md), is healthy and fresh
   (at most 15 minutes), and includes the canonical HPA manifest digests from:

   ```bash
   scripts/dr-standby-capacity.sh manifest-digest --target promote
   scripts/dr-standby-capacity.sh manifest-digest --target demote
   ```

## Procedure

1. Record baseline traffic, replica lag, exact HPA min/max values, ready pod
   placement, and the current writer/replication identities.
2. Prove offline active/base HPA drift and archive the report:

   ```bash
   scripts/dr-standby-capacity.sh verify \
     --report artifacts/<change-id>/dr-verify.json
   ```

3. Exercise server-side HPA validation without mutation:

   ```bash
   scripts/dr-standby-capacity.sh promote \
     --context prod-us-west-2 \
     --region us-west-2 \
     --dry-run \
     --report artifacts/<change-id>/dr-promote-dry-run.json
   ```

   A dry run is `ready: true` only if independently observed live capacity is
   already at the requested state. Archive `ready: false` as useful evidence;
   do not rewrite it.

4. Promote application capacity for the drill:

   ```bash
   scripts/dr-standby-capacity.sh promote \
     --context prod-us-west-2 \
     --region us-west-2 \
     --report artifacts/<change-id>/dr-promote.json
   ```

   Confirm only the four HPAs changed and the report is `ready: true`.

5. Inject the approved primary failure without deleting data. Observe the
   health/DNS behavior and record read RTO.
6. Follow the data-tier promotion drill and create fresh cutover evidence with:
   west writer, west-to-east replication, accepted lag/RPO, west traffic
   target, healthy status, and the exact manifest digest.
7. Run the read-only prerequisite gate:

   ```bash
   scripts/dr-standby-capacity.sh cutover-check \
     --context prod-us-west-2 \
     --region us-west-2 \
     --evidence /secure/drill/cutover-evidence.json \
     --report artifacts/<change-id>/dr-cutover-check.json
   ```

8. After operator review of a `ready: true` report, the authorized operator
   explicitly performs the drill traffic change. Verify health, a real
   recommendation, and a write; record write RTO and RPO.
9. Follow [dr-failback.md](dr-failback.md): produce fresh east-facing evidence,
   run `failback-check`, explicitly restore data/traffic, prove west traffic is
   zero, and only then run `demote`.

## Record and archive

Archive together:

- every command report, including failed attempts;
- context identity, dependency, cutover, and failback evidence;
- image digest and source commit;
- operator approvals/actions for data and traffic;
- read/write RTO, accepted RPO, and observed deviations.

The reports contain schema version, command, UTC timestamp, context/region,
source commit, canonical manifest digest, checks and observations, capacity
change, readiness, and remaining operator actions. A report is evidence, not
authority: HPA is the script's only mutation surface; data, DNS, and traffic
remain operator-owned.
