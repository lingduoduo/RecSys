# Redis authentication

## Provisioning

`REDIS_PASSWORD` comes from the `redis-password` key of the `recsys-secrets` Secret, consumed by
the four serving workloads, the reconciliation CronJob, and the Redis and Sentinel StatefulSets
themselves. The outbox relay does not use Redis and is deliberately not given the credential.

```bash
kubectl -n recsys create secret generic recsys-secrets \
  --from-literal=redis-password="$(openssl rand -base64 32)" \
  --dry-run=client -o yaml | kubectl apply -f -
```

The secret reference is `optional: true`, so pods stay schedulable before it exists — but the
missing-Secret state is worse than "nothing runs". `optional: true` only makes Kubernetes omit an
env var it cannot resolve; it does not touch a literal `$(REDIS_PASSWORD)` reference embedded in a
container's `args`. So without the Secret, Redis, its replica, and Sentinel start with
`--requirepass`/`--masterauth`/`sentinel auth-pass` all set to the *literal string*
`$(REDIS_PASSWORD)` — a real, unrotatable password that is publicly known from reading the
manifest — while all five client workloads have no `REDIS_PASSWORD` at all and crash-loop on the
startup guard. Provision the Secret before applying `k8s/base`, not after.

## Local development

`scripts/run-microservices-local.sh` exports `REDIS_ALLOW_NO_AUTH=true`, and Surefire sets it for
the test suite. Set it yourself if you run a service by hand against a passwordless Redis:

```bash
export REDIS_ALLOW_NO_AUTH=true
```

## Rotation

`requirepass` holds exactly one password, so rotation is a coordinated restart rather than an
overlap window. This is the accepted cost of `requirepass` over an ACL file; if rotation frequency
ever makes it unacceptable, moving the `default` user to an `aclfile` buys multi-password overlap.

Expect Redis to be unreachable for the duration. Everything degrades to its no-Redis path, which is
not a full outage but is a visible one.

1. Update the Secret:

   ```bash
   kubectl -n recsys patch secret recsys-secrets \
     -p "{\"stringData\":{\"redis-password\":\"$(openssl rand -base64 32)\"}}"
   ```

2. Restart Redis and Sentinel first — clients cannot authenticate until the server has the new
   password:

   ```bash
   kubectl -n recsys rollout restart statefulset/redis-primary
   kubectl -n recsys rollout restart statefulset/redis-replica
   kubectl -n recsys rollout restart statefulset/redis-sentinel
   ```

3. Then the clients:

   ```bash
   kubectl -n recsys rollout restart deployment/recsys-api-gateway
   kubectl -n recsys rollout restart deployment/recsys-catalog-serving
   kubectl -n recsys rollout restart deployment/recsys-model-serving
   kubectl -n recsys rollout restart deployment/recsys-online-serving
   ```

   The reconciliation CronJob picks the new value up on its next scheduled run.

## ElastiCache

Both EKS overlays set `REDIS_TLS: "false"` explicitly. Flipping it to `"true"` requires the
ElastiCache cluster to have encryption-in-transit enabled and its AUTH token placed in
`redis-password`. The client verifies the certificate against the JVM truststore, which already
trusts the Amazon Root CA that ElastiCache certificates chain to — there is no truststore to
configure and no verification bypass to enable.
