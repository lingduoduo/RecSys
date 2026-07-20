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
  dev posture so the stack runs without a Cognito pool or key Secret.
- **EKS (all regions)** (`k8s/eks-shared/configmap-patch.yaml`): `GATEWAY_ALLOW_ANONYMOUS: "false"`
  and `GATEWAY_API_KEYS` injected from the `recsys-gateway-auth` Secret
  (`k8s/eks-shared/gateway-auth-patch.yaml`, `optional: false`). The pod will not schedule until
  the Secret exists — this is intentional.

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
