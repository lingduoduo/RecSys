# Streaming job Redis authentication — design

Give the Flink and Spark jobs the ability to authenticate to Redis, and put the credential-building
logic somewhere it can actually be tested.

## The gap

Both jobs connect with no credentials at all:

- `src/main/java/com/recsys/online/flink/OnlineFeatureStreamingJob.java:949` —
  `RedisClient.create(RedisURI.create(host, port))`
- `src/main/java/com/recsys/training/rulebased/ItemEmbeddingJob.java:172` — the same

No username, no password, no TLS. Every other Redis client in the system goes through
`LettuceClientFactory`, which reads `REDIS_USERNAME`, `REDIS_PASSWORD` and `REDIS_TLS` and refuses
to connect unauthenticated unless `REDIS_ALLOW_NO_AUTH` is set. These two bypass it entirely.

**This is not a hardening item, it is an outage waiting on a deploy.** The moment PR #274's
`requirepass` is actually applied, both jobs fail `NOAUTH`. `OnlineFeatureStreamingJob` writes
`u2vEmb:*` and `topk:*`, which every serving path reads, and `ItemEmbeddingJob` writes `i2vEmb:*`.

It also invalidates an assumption in the Redis ACL work (#284): that design left `default` at
`+@all` specifically so these jobs would keep working, reasoning that they authenticate as
`default`. They do not authenticate at all.

Found by the 2026-08-09 posture audit, `docs/system_design/22_Data_Leakage_Posture.md`.

## What this repo can and cannot fix

**No deployment configuration for either job exists here.** There is no Kubernetes manifest, no
`docker-compose` service, and no submit script for them — they are built from this source and
submitted by something outside the repo. They read configuration through Flink's `params` with
defaults, e.g. `params.get("redis.host", "localhost")` (`OnlineFeatureStreamingJob:70`).

So the split is:

- **In scope:** making the jobs *able* to authenticate.
- **Out of scope:** the credentials themselves, which belong wherever the jobs are submitted.

Shipping this does not by itself remove the outage risk. It makes removing it possible, and the
documentation must say so rather than implying the gap is closed.

## The fix

**New parameters, same precedence as the existing ones.** Each job gains `redis.username`,
`redis.password` and `redis.tls`, read through `params.get(name, default)` exactly as `redis.host`
already is, defaulting to the `REDIS_USERNAME` / `REDIS_PASSWORD` / `REDIS_TLS` environment
variables so a job inherits the same configuration a service would.

A blank username means legacy default-user `AUTH`; a non-blank one means a Redis 6+ ACL login. That
is the rule `LettuceClientFactory.withAuth` (`:218-223`) already applies, so a job and a service
authenticate identically against the same server.

**The logic goes in the compiled tree — this is the load-bearing part of the design.**
`online/flink/` and `training/rulebased/` are excluded from the Maven compile because they need
Flink and Spark classpaths. Anything written there cannot be compiled, cannot be tested, and cannot
fail a build. This project has shipped unverified code into exactly that kind of hole twice already
— the Splunk HEC integration and the CDN edge tests — and both times the cost was discovering the
problem much later.

So the `RedisURI` construction moves into a small class beside `LettuceClientFactory`, in the
compiled tree, and each job calls it in one line. `LettuceClientFactory.standaloneUri(host, port,
username, password, tls, timeoutMs)` at `:226` already does precisely this and is package-private;
the work is to expose an equivalent entry point deliberately rather than widening that method by
accident, and to give it its own tests.

That leaves exactly one unverified line per job — the call — instead of the whole credential path.

## Testing

The shared builder gets unit tests over the combinations that decide whether a connection succeeds:
username present and absent, password present and absent, the two crossed, TLS on and off, and
blank versus absent (they must behave identically, since a Flink parameter default and an unset
environment variable arrive differently). Each asserts the resulting `RedisURI` matches what
`LettuceClientFactory` produces for the same inputs — the point is that the two paths cannot drift.

Non-docker, in the `resilience` profile.

**Nothing can test the jobs themselves.** They do not compile in this build. The tests cover the
logic they call, not their use of it, and the spec says so rather than letting a green gate imply
otherwise.

## Documentation

`docs/system_design/22_Data_Leakage_Posture.md` — gap 2 moves from "the jobs cannot authenticate"
to "the jobs can authenticate; whoever submits them must pass the credentials", with the outage risk
kept, because it is not gone until that happens.

A note wherever the streaming jobs are described, stating the new parameters and that an
authenticated Redis requires them. `docs/runbooks/redis-auth.md` is the natural home, beside the
existing rotation procedure.

## What this does not do

- **It does not configure anything.** No credentials are set, because nothing in this repo submits
  these jobs.
- **It does not verify the jobs run.** They are outside the compile; the call sites ship unverified.
- **It does not give the jobs their own ACL user.** They authenticate as `default`, which #284 left
  at `+@all` for this reason. Narrowing that requires knowing where they run, which is the same
  unknown that scopes everything above.
