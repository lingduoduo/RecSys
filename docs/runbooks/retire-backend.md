# Runbook: Retire (Decommission) the Recsys Backend

> **IRREVERSIBLE / POINT OF NO RETURN.** This procedure permanently shuts down all
> four backend services and their infrastructure. All runtime state (Redis
> embeddings, topk stores, online-learner params) is **discarded** — it is
> regenerable from the offline/streaming pipelines and is NOT backed up here.

## Prerequisites
- `kubectl` configured for the target cluster/context.
- `aws` CLI configured (for Cloud Map / ALB / IRSA cleanup).
- Confirmation that all external callers have been migrated off (the wind-down is
  graceful, but new traffic should already be gone).
- Confirmation that no consumer still depends on the live Redis/topk data.

## Procedure
The script `scripts/retire-backend.sh` automates the ordered teardown. Always do a
dry run first:

    scripts/retire-backend.sh --dry-run --namespace <ns> --context <ctx>

Then execute:

    scripts/retire-backend.sh --namespace <ns> --context <ctx>

### Phase mapping
| Script phase | What happens | Verify |
|---|---|---|
| 0 Pre-flight | Context check, replica snapshot, typed confirmation | You typed the namespace |
| 1 Gateway drain | `recsys-api-gateway` scaled to 0; ALB deregisters; in-flight drains | `verify_drained` passes; no external traffic reaches backends |
| 2 Backend drain | `recsys-model-/online-/catalog-serving` scaled to 0 in parallel; each flushes Kafka publishers, final learner flush, closes ONNX/Redis | `verify_drained` passes for each |
| 3 Infra teardown | Redis StatefulSets (`app=redis`) deleted (skipped with `--keep-infra`) | Redis resources gone |
| 4 Manifest delete | `kubectl delete -k k8s/base`; remove Cloud Map / ALB TG / IRSA | Manifests deleted |
| 5 Verify clean | `kubectl get all` report | No remaining pods/services |

### Streaming pipeline (separate repo)
The Flink streaming **jobs** live in `Recsys-Streaming-Pipeline`, NOT this repo.
Stop those jobs **before** their Kafka brokers, following that repo's own runbook.
The Kafka *publishers* inside this backend already flush on shutdown (Phase 2).

## If a tier won't drain
`verify_drained` aborts (no force-kill) if a tier exceeds `--drain-timeout`
(default 60s). Investigate the stuck pod (`kubectl describe`, logs). Once safe,
re-run the script — it is idempotent and resumes from where it stopped.

## Options
- `--keep-infra` — stop after Phase 2 (leave Redis/cloud infra up). Useful for a
  staged retirement: dry out the app tier first, tear down infra later.
- `--drain-timeout SEC` — adjust the per-tier drain budget.
- `--dry-run` — print every command without executing.

## Rollback
Before Phase 4 (manifest delete), retirement is reversible: scale the deployments
back up (`kubectl scale deploy <name> --replicas=N`). After Phase 4, redeploy from
`k8s/base` to restore the system (state will be empty and re-seed from the
pipelines).
