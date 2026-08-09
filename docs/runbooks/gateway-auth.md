# Gateway authentication (fail-closed)

The API gateway (`MicroserviceGatewayServer`) authenticates every non-public request via
`GatewayAuthenticator`. It accepts an `x-api-key` header (matched against `GATEWAY_API_KEYS`)
or an `Authorization: Bearer` Cognito JWT (verified against `GATEWAY_COGNITO_ISSUER` /
`GATEWAY_COGNITO_AUDIENCE`).

## Fail-closed contract

A gateway with **no** API keys and **no** Cognito issuer authenticates nobody: every caller
collapses to the `anonymous` principal, which turns any downstream authorization gap into an
*unauthenticated* one and collapses per-caller rate limiting into a single shared bucket.

To prevent this from shipping by accident, `GatewayAuthenticator.fromEnvironment` **refuses to
start** when nothing is configured, unless anonymous access is explicitly opted in:

| `GATEWAY_API_KEYS` / Cognito | `GATEWAY_ALLOW_ANONYMOUS` | Result |
|---|---|---|
| set | (ignored) | Auth enabled |
| unset | `true` | Auth disabled — all callers anonymous (WARN logged) |
| unset | unset / `false` | **Startup fails** with `IllegalStateException` |

- **Local / base** (`k8s/base/configmap.yaml`): `GATEWAY_ALLOW_ANONYMOUS: "true"` — a deliberate
  dev posture so the stack runs without a Cognito pool or key Secret. Anonymous by design does
  **not** mean internet-reachable: the base gateway `Service` is `ClusterIP`, so nothing outside
  the cluster can reach it. Get to it with:

  ```bash
  kubectl port-forward svc/recsys-api-gateway 8010:80 -n recsys
  ```

  then call `http://localhost:8010` as if it were the gateway directly. `GatewayExposureManifestTest`
  pins this pairing: an anonymous gateway in `k8s/base` must not be `LoadBalancer`/`NodePort` or
  carry `aws-load-balancer-scheme: internet-facing`.
- **EKS (all regions)** (`k8s/eks-shared/configmap-patch.yaml`): `GATEWAY_ALLOW_ANONYMOUS: "false"`
  and `GATEWAY_API_KEYS` injected from the `recsys-gateway-auth` Secret
  (`k8s/eks-shared/gateway-auth-patch.yaml`, `optional: false`). The pod will not schedule until
  the Secret exists — this is intentional. Both region overlays expose the gateway to the
  internet via a WAF Ingress annotated `alb.ingress.kubernetes.io/scheme: internet-facing`
  (`waf-api-gateway-ingress.yaml`), which is only safe because `eks-shared` also flips
  `GATEWAY_ALLOW_ANONYMOUS` to `"false"`. **Adding internet-facing exposure to any overlay
  requires setting that flag to `"false"` in the same change.** `GatewayExposureManifestTest`
  enforces this in the `-Presilience` PR gate: any overlay whose combined manifest text (region
  overlay + `eks-shared`, since the flag lives only in the latter) contains an internet-facing
  signal — the ALB annotation above, the older `aws-load-balancer-scheme: internet-facing`
  Service annotation, `type: LoadBalancer`, or `type: NodePort` — without also containing
  `GATEWAY_ALLOW_ANONYMOUS: "false"` fails the build. Note this is a text-coupling check, not a
  rendered kustomization: it cannot see a patch that fails to apply.

## Enabling auth in a cloud region

Create the Secret **before** rollout (the Deployment references it with `optional: false`):

```bash
kubectl -n recsys create secret generic recsys-gateway-auth \
  --from-literal=api-keys="$(openssl rand -hex 24),$(openssl rand -hex 24)"
```

Multiple comma-separated keys are supported so keys can be rotated with no downtime: add the new
key, roll clients, then drop the old key.

### Cognito JWT (optional, per-region)

The Cognito issuer URL is region-specific, so set it in the **per-region** overlay
(`k8s/eks/` or `k8s/eks-us-west-2/`), not `eks-shared`:

```yaml
GATEWAY_COGNITO_ISSUER: "https://cognito-idp.us-east-1.amazonaws.com/<pool-id>"
GATEWAY_COGNITO_AUDIENCE: "<app-client-id>"   # REQUIRED when issuer is set
GATEWAY_COGNITO_TOKEN_USE: "access"           # or "id"
```

API keys and Cognito can be enabled together; either valid credential is accepted.

## Verifying

- Startup log line `Gateway API-key authentication enabled` (or the loud `Gateway authentication
  is DISABLED …` WARN if anonymous is opted in).
- A request with no credential to a non-public path returns `401` with `WWW-Authenticate: Bearer`.
- Public paths (`GATEWAY_PUBLIC_PATHS`, default `/health,/api/catalog/item,/api/catalog/similar`)
  remain reachable anonymously — keep this list to exact paths only (see the DANGER note in
  `k8s/base/configmap.yaml`).

## Calling an operator-class route through the gateway

`BackendRoutePolicy` marks a handful of control-plane routes `OPERATOR` —
`/api/catalog/setembedding`, the model `activate`/`rollback`/`preload` endpoints, and
`/api/online/online/ops` — on top of the two the online-serving Deployment already gates
directly (`docs/system_design/20_AuthN_AuthZ.md` §5, §11). Reaching any of them through the
gateway requires **both** an ordinary gateway credential (API key or JWT) **and** the operator
token in `X-Admin-Token`:

```bash
curl -H "X-API-Key: $GATEWAY_API_KEY" \
     -H "X-Admin-Token: $SHARD_ADMIN_TOKEN" \
     -X POST https://<gateway>/api/model/api/v1/model/versions/activate \
     -d '{"version":"..."}'
```

**Listing model versions is `OPERATOR` too.** `GET /api/model/api/v1/model/versions` (equally
`/api/v1/model/versions` on 8080) is in the same class as `activate`/`rollback`/`preload`, so an
operator who used to list versions with only an API key now needs `X-Admin-Token` as well — a read
that worked yesterday returns `403 {"error":"operator token required"}` today, and that is the
change, not a broken credential.

A `403 {"error":"operator token required"}` here means one of two things: the header was missing
or wrong, or `SHARD_ADMIN_TOKEN` is unset on the gateway pod — in which case the guard authorizes
nobody and **every** `OPERATOR` route rejects **every** caller, token or no token. Check the
gateway's startup log for the unset-token warning, and confirm the `recsys-online-admin` Secret
exists and is mounted (`kubectl -n recsys get secret recsys-online-admin`,
`kubectl -n recsys exec deploy/recsys-api-gateway -- env | grep SHARD_ADMIN_TOKEN`).

**Break-glass:** if `SHARD_ADMIN_TOKEN` is genuinely unset (Secret not yet provisioned, or
deliberately withheld), operator paths are unreachable through the gateway by design — there is no
token that unlocks them. The only way to reach them at all in that state is a direct connection to
the backend pod (port-forward past the gateway), which also bypasses gateway authentication
entirely. That is a deliberate consequence of the backends authenticating nobody
(`docs/system_design/20_AuthN_AuthZ.md` sharp edge 6), not a supported operational path — use it
only to unblock an emergency, and prefer creating the Secret and rolling the gateway instead:

```bash
kubectl -n recsys create secret generic recsys-online-admin \
  --from-literal=admin-token="$(openssl rand -hex 32)"
```
