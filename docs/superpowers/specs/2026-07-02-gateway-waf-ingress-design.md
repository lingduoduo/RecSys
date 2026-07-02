# Gateway WAF Ingress — Design

**Date:** 2026-07-02
**Status:** Approved (pending spec review)
**Scope:** `k8s/eks/` overlay (new WAF ALB Ingress + a gateway-Service patch +
`kustomization.yaml` wiring) and a new operator runbook. No application-code
changes, no `k8s/base/` changes, no new build tooling.

## Goal

Put an AWS WAFv2 WebACL in front of the public API gateway. Because AWS WAF
attaches to an ALB (L7) — not to the NLB the gateway uses today — replace the
NLB with a WAF-protected ALB Ingress as the sole external entry, so there is no
unprotected bypass.

## Context

- The gateway is exposed by `k8s/base/api-gateway.yaml` as a `Service`
  `type: LoadBalancer` with `service.beta.kubernetes.io/aws-load-balancer-type:
  external` + `nlb-target-type: ip` — i.e. an internet-facing **NLB** (L4) on
  HTTP:80 → gateway pods on 8010.
- **AWS WAF cannot attach to an NLB.** It attaches to an ALB, API Gateway,
  CloudFront, or App Runner. So WAF requires fronting the gateway with an ALB.
- The AWS Load Balancer Controller provisions an ALB from a Kubernetes `Ingress`
  and attaches a WAFv2 WebACL via the
  `alb.ingress.kubernetes.io/wafv2-acl-arn` annotation. The WebACL itself is not a
  Kubernetes object — it is created out-of-band (Terraform/console) and referenced
  by ARN.
- There is no Ingress / ALB / Terraform in the repo today; the deploy pattern is
  Kustomize base + eks overlay. This change stays in that pattern (eks overlay
  only), matching how Cloud Map and other EKS specifics are handled.

## Design

All changes live in `k8s/eks/`; `k8s/base/` is unchanged (it keeps the NLB Service
for non-EKS renders).

### Component 1 — Gateway Service → ClusterIP (drop the NLB)

Patch the `recsys-api-gateway` Service in the eks overlay from
`type: LoadBalancer` to `type: ClusterIP`, and remove the now-inert
`aws-load-balancer-*` annotations. A JSON6902 patch (inline in
`kustomization.yaml`, `target: {kind: Service, name: recsys-api-gateway}`):

```yaml
- op: replace
  path: /spec/type
  value: ClusterIP
- op: remove
  path: /metadata/annotations
```

The gateway Service's only annotations in base are the NLB ones, so removing the
annotation map is safe. As a `ClusterIP` Service it still exposes port 80 →
targetPort `http` (8010), which the ALB targets in IP mode.

### Component 2 — WAF-protected ALB Ingress

New `k8s/eks/waf-api-gateway-ingress.yaml`, the sole public entry:

```yaml
apiVersion: networking.k8s.io/v1
kind: Ingress
metadata:
  name: recsys-api-gateway-waf
  namespace: recsys
  annotations:
    # Deprecated but universally supported; avoids assuming a pre-existing
    # IngressClass resource. Modern alternative: spec.ingressClassName: alb.
    kubernetes.io/ingress.class: alb
    alb.ingress.kubernetes.io/scheme: internet-facing
    alb.ingress.kubernetes.io/target-type: ip
    alb.ingress.kubernetes.io/listen-ports: '[{"HTTP":80}]'
    alb.ingress.kubernetes.io/healthcheck-path: /health
    # Regional AWS WAFv2 WebACL ARN. Provision the WebACL out-of-band
    # (Terraform/console) and set this before applying — see
    # docs/runbooks/waf-webacl.md. The Ingress renders without a real ARN, but
    # the ALB Controller rejects an invalid ARN at apply time.
    alb.ingress.kubernetes.io/wafv2-acl-arn: arn:aws:wafv2:us-east-1:123456789012:regional/webacl/recsys-api-gateway/REPLACE_ME
spec:
  rules:
    - http:
        paths:
          - path: /
            pathType: Prefix
            backend:
              service:
                name: recsys-api-gateway
                port:
                  number: 80
```

Wired into `k8s/eks/kustomization.yaml` `resources:`. `target-type: ip` routes
directly to gateway pod IPs (port 8010 via the Service's `http` targetPort);
healthcheck defaults to the traffic port with path `/health`. HTTP:80 only
(parity with the prior NLB — no TLS/ACM prerequisite).

### Component 3 — WebACL provisioning runbook

New `docs/runbooks/waf-webacl.md` documenting the operator prerequisite (Kustomize
cannot create the WebACL):

- Create a **regional** WAFv2 WebACL (`--scope REGIONAL`; **not** CLOUDFRONT) in the
  same region as the EKS ALB, default action **Allow**.
- Recommended rules (each action **Block**), in order:
  1. `AWSManagedRulesAmazonIpReputationList` (managed)
  2. `AWSManagedRulesKnownBadInputsRuleSet` (managed)
  3. `AWSManagedRulesCommonRuleSet` (managed — OWASP-style)
  4. A **rate-based rule**: limit ~2000 requests / 5-minute window per source IP.
- Copy the resulting WebACL ARN into the Ingress
  `alb.ingress.kubernetes.io/wafv2-acl-arn` annotation, then apply the overlay.
- Include the `aws wafv2 create-web-acl` / `create-ip-set` invocation shapes and a
  note that the WebACL region must match the ALB region.

## Error Handling / Operational Notes

- The Ingress **renders** with the placeholder ARN, but the ALB Controller
  **rejects** an invalid/nonexistent WebACL ARN when reconciling — so the ARN must
  be real before apply. This is called out in the manifest comment and the runbook.
- **DNS cutover:** replacing the NLB with an ALB changes the public endpoint
  (ALB DNS name instead of NLB). External DNS (Route 53 / the record that pointed
  at the NLB) must be repointed at the ALB. Documented in the runbook.
- The **AWS Load Balancer Controller** must be installed in the cluster (same
  class of prerequisite as the Cloud Map controller the overlay already assumes).

## Verification

Config-only; validated by rendering:

- `kubectl kustomize k8s/eks` renders without error; the `recsys-api-gateway`
  Service is `type: ClusterIP` with **no** `aws-load-balancer-*` annotations and no
  `LoadBalancer` type; the `recsys-api-gateway-waf` Ingress is present with the
  `wafv2-acl-arn` annotation and routes `/` → `recsys-api-gateway:80`.
- `kubectl kustomize k8s/base` is unchanged from before (gateway still
  `type: LoadBalancer` with the NLB annotations — base portability preserved).

## Out of Scope (YAGNI)

- WebACL as IaC — would add Terraform or the ACK wafv2 controller, new tooling
  outside the repo's Kustomize-only pattern. Referenced by ARN + runbook instead.
- TLS/HTTPS on the ALB (HTTP:80 chosen for parity; would need an ACM cert ARN).
- Installing the AWS Load Balancer Controller (a cluster prerequisite).
- Per-path WAF rules or geo/IP allowlists beyond the documented baseline.

## Cross-cutting

- Changes confined to `k8s/eks/` + `docs/runbooks/`; base and application code
  untouched.
- One commit per implementation task; feature branch, PR to `main` (never commit
  to `main` directly).
