# Runbook: DR Game Day

Periodic drill (quarterly) to prove the DR path works. Run in a maintenance window
with stakeholders notified.

## Objectives

- Prove Route53 fails DNS over to us-west-2 automatically.
- Prove the standby serves reads with no human action.
- Prove the data-tier promotion runbook restores writes within the RTO target.
- Measure actual RTO (reads and writes) and RPO.

## Procedure

1. **Baseline**: record current traffic, us-west-2 replica lag, HPA replica counts.
2. **Inject failure**: make the primary ALB health check fail (e.g. temporarily
   block the health-check path at the primary, or scale the primary gateway to 0
   in a non-prod mirror). Do NOT delete data.
3. **Observe DNS failover**: poll `dig +short api.recsys.example.com` until it
   resolves to the us-west-2 ALB. Record elapsed time = **read RTO**.
4. **Verify reads**: `curl -fsS https://api.recsys.example.com/health` and a real
   recommendation request succeed.
5. **Promote data tier**: run `dr-data-tier-promotion.md`. Record elapsed time =
   **write RTO**.
6. **Verify writes**: a feedback request persists in us-west-2.
7. **Fail back**: run `dr-failback.md`. Confirm return to steady state.

## Record

Capture read RTO, write RTO, RPO, and any deviations. File follow-ups for anything
that missed target (esp. warm-standby sizing — see the design's Risks section).
