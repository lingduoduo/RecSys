# Runbook: DR Failback (us-west-2 → us-east-1)

Return to us-east-1 as primary after it recovers. Do this deliberately during a
low-traffic window — it is a planned cutover, not an emergency. For how DR fits the
broader resilience model see the [Fault Tolerance investigation](../system_design/18_Fault_Tolerance.md).

## 1. Re-establish us-east-1 as a replica

- Rebuild the Aurora Global Database with us-east-1 as a secondary of the current
  (us-west-2) primary; let it catch up.
- Rebuild the ElastiCache Global Datastore with us-east-1 as secondary.
- Confirm replication lag is near zero before proceeding.

## 2. Deploy / warm us-east-1

```bash
scripts/set-eks-image-digest.sh --tag <current-release-tag>
kubectl --context <us-east-1-ctx> apply -k k8s/eks
kubectl --context <us-east-1-ctx> -n recsys rollout status deploy
```

## 3. Reverse the promotion, then flip DNS

- Promote us-east-1 Aurora + ElastiCache to primary (per `dr-data-tier-promotion.md`,
  reversed) and repoint streaming producers back to us-east-1.
- Re-enable the Route53 PRIMARY record's health check so it points back to
  us-east-1. Because it is the PRIMARY failover record, Route53 returns to it once
  healthy.
- Verify: once the CDN rollout (`docs/runbooks/cdn-operations.md`) is complete, the failover
  record lives on `origin.recsys.example.com`, not the public hostname — check
  `dig +short origin.recsys.example.com` resolves to the us-east-1 ALB, and confirm
  `https://app.recsys.example.com/health` (the CloudFront alias) is green. Pre-rollout, check
  `dig +short api.recsys.example.com` resolves to the us-east-1 ALB directly and `/health` is
  green there. See `docs/runbooks/dr-regional-failover.md` for the full topology.

## 4. Return us-west-2 to warm standby

- Confirm us-west-2 is back to secondary (read replica) and HPA minReplicas are the
  warm-standby values (gateway 1, catalog 1, model 2, online 1).
- Demote the standby capacity back to the warm-standby floor (it was promoted to the
  primary baseline during failover):
  ```bash
  scripts/dr-standby-capacity.sh demote --context <us-west-2-ctx>
  ```
  This re-applies `k8s/eks-us-west-2`, restoring minReplicas 1/1/2/1.
