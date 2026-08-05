# Redis authentication

## Provisioning

`REDIS_PASSWORD` comes from the `redis-password` key of the `recsys-secrets` Secret, consumed by
the four serving workloads, the reconciliation CronJob, and the Redis and Sentinel StatefulSets
themselves. The outbox relay does not use Redis and is deliberately not given the credential.

```bash
kubectl -n recsys patch secret recsys-secrets \
  -p "{\"stringData\":{\"redis-password\":\"$(openssl rand -base64 32)\"}}"
```

`patch`, not the `create --dry-run=client -o yaml | apply -f -` idiom the other runbooks use.
`recsys-secrets` is shared — it also holds `MYSQL_PASSWORD`, `ONLINE_CONSISTENCY_TOKEN_SECRET`, and
the recommendation-cursor signing keys — and `apply` replaces the Secret's whole data map with what
the applied document contains. A `create --from-literal=redis-password=...` document contains only
that one key, so applying it deletes the others. The single-key Secrets those other runbooks manage
(`recsys-gateway-origin-secret`, `recsys-online-admin`) have nothing to lose that way; this one does.

The password must avoid `|`, `&`, `\` and `$(`. The Sentinel config is produced by
`sed "s|__REDIS_PASSWORD__|$REDIS_PASSWORD|"`, so `|` closes the substitution early, `&` expands to
the whole match, and `\` starts an escape — each yields a Sentinel that authenticates with something
other than the password you set. `$(` is unsafe for two separate reasons: every command here embeds
the password in a double-quoted shell string, where `$(...)` is command substitution your shell runs
before `kubectl` ever sees it; and the primary and replica take theirs through Kubernetes `$(VAR)`
substitution in `args`, so a password written in that same syntax is unreadable to anyone auditing
those args against the Secret. `openssl rand -base64` emits only `+`, `/` and `=`, so the generator
above is safe — the constraint bites when someone substitutes a hand-chosen password.

The secret reference is `optional: true`, so pods stay schedulable before it exists — but the
missing-Secret state is worse than "nothing runs", and it fails two different ways. The primary and
replica set `--requirepass`/`--masterauth` via Kubernetes' `args`-level `$(REDIS_PASSWORD)`
substitution, which `optional: true` does not touch: an unresolvable reference is left as the
*literal string* `$(REDIS_PASSWORD)`, so both run reachable with a real, manifest-visible password.
Sentinel instead gets its password from an init container's `sed` substitution inside `sh -c` —
ordinary shell parameter expansion, not Kubernetes substitution — so an absent env var renders the
malformed line `sentinel auth-pass mymaster ` with no argument. All five client workloads,
meanwhile, have no `REDIS_PASSWORD` at all and crash-loop on the startup guard.

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

> **Both regions.** `k8s/eks-us-west-2` is a warm standby dialing its *own* Redis with its own copy
> of the `redis-password` key, so the credential is per-region state. Rotating only us-east-1 leaves
> the standby holding a password its Redis no longer accepts, and nothing reports it until a
> failover — at which point every promoted pod crash-loops on a credential you rotated weeks
> earlier. Run every step below in **both** contexts before moving to the next step, the same
> two-context discipline as [cdn-operations.md](cdn-operations.md).

1. Update the Secret. Generate the value once and apply the *same* value in both contexts — the two
   regions must not drift apart, and a second `openssl rand` would give them different passwords:

   ```bash
   NEW_REDIS_PASSWORD="$(openssl rand -base64 32)"

   kubectl --context <us-east-1-ctx> -n recsys patch secret recsys-secrets \
     -p "{\"stringData\":{\"redis-password\":\"$NEW_REDIS_PASSWORD\"}}"
   kubectl --context <us-west-2-ctx> -n recsys patch secret recsys-secrets \
     -p "{\"stringData\":{\"redis-password\":\"$NEW_REDIS_PASSWORD\"}}"
   ```

2. Restart Redis and Sentinel first — clients cannot authenticate until the server has the new
   password:

   ```bash
   for ctx in <us-east-1-ctx> <us-west-2-ctx>; do
     kubectl --context "$ctx" -n recsys rollout restart statefulset/redis-primary
     kubectl --context "$ctx" -n recsys rollout restart statefulset/redis-replica
     kubectl --context "$ctx" -n recsys rollout restart statefulset/redis-sentinel
   done
   ```

   On an EKS overlay these StatefulSets are scaled to 0 and the step is a no-op: the server there is
   ElastiCache, and its side of the rotation is
   `aws elasticache modify-replication-group --auth-token "$NEW_REDIS_PASSWORD"
   --auth-token-update-strategy ROTATE` against that region's replication group, run before step 3.

3. Then the clients:

   ```bash
   for ctx in <us-east-1-ctx> <us-west-2-ctx>; do
     for d in recsys-api-gateway recsys-catalog-serving recsys-model-serving recsys-online-serving; do
       kubectl --context "$ctx" -n recsys rollout restart deployment/"$d"
       kubectl --context "$ctx" -n recsys rollout status deployment/"$d"
     done
   done
   ```

   The reconciliation CronJob picks the new value up on its next scheduled run.

4. Confirm the standby actually came back. A warm standby serves no traffic, so a failed rollout
   there is invisible in every dashboard that watches request rates:

   ```bash
   kubectl --context <us-west-2-ctx> -n recsys get pods
   ```

   Any pod in `CrashLoopBackOff` is the startup guard reporting that this region's Redis did not get
   the same password its clients did.

## ElastiCache

**Neither EKS overlay is applyable as it stands.** This is a prerequisite, not future work.
`k8s/eks-shared` scales the in-cluster Redis to 0, so on EKS every client dials ElastiCache — and
both overlays still carry a `<placeholder>` `REDIS_HOST` and `REDIS_TLS: "false"`. ElastiCache
cannot hold an AUTH token without encryption-in-transit, so with the guard now requiring a
password there is no combination of those settings that works:

| What you set | What you get |
|---|---|
| No `redis-password` key | Every pod `CrashLoopBackOff`s on the startup guard, with the guard's message naming `REDIS_PASSWORD` in the logs. |
| A password, cluster has no AUTH token | `ERR Client sent AUTH, but no password is set` on the first command. The pod starts, then fails every Redis call. |
| A password, cluster has an AUTH token | That cluster necessarily has transit encryption on, so `REDIS_TLS: "false"` fails the TLS handshake — a connection-level error, not an auth error, which is the one that gets misdiagnosed. |

Before applying either overlay, create the region's ElastiCache cluster **with encryption-in-transit
enabled and an AUTH token**, then land all three of these in the same change:

1. `REDIS_HOST` (and `REDIS_REPLICA_NODES` in us-east-1) set to the real endpoints.
2. `REDIS_TLS: "true"` in that overlay's `redis-elasticache-patch.yaml`.
3. The AUTH token in the `redis-password` key of `recsys-secrets`, in that region's context.

They are only valid as a set. Landing any one of them alone produces one of the three failures
above. Each region has its own cluster and its own token — see the both-regions note under
[Rotation](#rotation).

The client verifies the certificate against the JVM truststore, which already trusts the Amazon Root
CA that ElastiCache certificates chain to — there is no truststore to configure and no verification
bypass to enable.
