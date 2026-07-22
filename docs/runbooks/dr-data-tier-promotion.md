# Runbook: DR Data-Tier Promotion (us-west-2)

Run this after DNS has failed over (see `dr-regional-failover.md`) to restore the
**write** path. Reads already work from the replicas. For how DR fits the broader
resilience model see the [Fault Tolerance investigation](../../18_Fault_Tolerance.md).

## 1. Promote Aurora Global Database

> Only if MySQL is enabled (`MYSQL_ENABLED=true`). The default deployment runs with
> MySQL disabled and writes only to Redis + SQS — in that case skip to step 2.

- Console/CLI: on the us-west-2 secondary cluster, perform "Remove from Global"
  (managed failover) or "Promote" to make it a standalone writable cluster.
- Confirm the writer endpoint is available:
  `aws rds describe-db-clusters --region us-west-2 --db-cluster-identifier <id>`
- If the app reads the DB endpoint from config, ensure `k8s/eks-us-west-2` points
  at the promoted writer endpoint (update the relevant ConfigMap/Secret and
  `kubectl apply -k k8s/eks-us-west-2`).

## 2. Promote ElastiCache Global Datastore

- Console/CLI: "Failover Global Datastore" to make the us-west-2 cluster primary
  (writable).
- Verify `k8s/eks-us-west-2/redis-elasticache-patch.yaml` `REDIS_HOST` points at
  the now-primary us-west-2 endpoint; re-apply if it changed.

## 3. Repoint streaming producers

- There is no cross-region broker replication. Point event producers (the
  streaming/ingestion tier) at the us-west-2 SQS queue / Kafka endpoint.
- Confirm the us-west-2 Flink consumers are processing:
  check the online feature store keys are advancing in the us-west-2 Redis.

## 4. Verify write path

- Exercise a feedback/write request end-to-end against
  `https://api.recsys.example.com` and confirm it persists (Aurora row / Redis
  key written in us-west-2).
