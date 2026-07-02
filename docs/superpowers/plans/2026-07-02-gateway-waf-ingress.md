# Gateway WAF Ingress Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Front the public API gateway with an AWS WAFv2 WebACL by replacing its NLB with a WAF-protected ALB Ingress (sole external entry) in the EKS overlay.

**Architecture:** All changes in `k8s/eks/`: patch the `recsys-api-gateway` Service to `ClusterIP` (dropping the NLB annotations), add a `recsys-api-gateway-waf` ALB Ingress carrying `alb.ingress.kubernetes.io/wafv2-acl-arn`, wire both into `kustomization.yaml`, and add an operator runbook for provisioning the WebACL. `k8s/base/` and application code are untouched.

**Tech Stack:** Kubernetes, Kustomize (via `kubectl kustomize`, embedded v5.7.1), AWS Load Balancer Controller (ALB Ingress), AWS WAFv2 (referenced by ARN).

## Global Constraints

- Edit only files under `k8s/eks/` and add one doc under `docs/runbooks/`. No `k8s/base/` changes, no application-code changes, no new build tooling.
- The WAF-protected ALB is the **sole** external entry: the gateway Service becomes `ClusterIP` (no `LoadBalancer`/NLB). No unprotected bypass.
- HTTP:80 only (parity with the prior NLB; no TLS/ACM).
- The WebACL is referenced by ARN via `alb.ingress.kubernetes.io/wafv2-acl-arn`; it is NOT created by Kustomize (documented as an operator prerequisite in the runbook). Placeholder ARN: `arn:aws:wafv2:us-east-1:123456789012:regional/webacl/recsys-api-gateway/REPLACE_ME`.
- Ingress uses the `kubernetes.io/ingress.class: alb` annotation (avoids assuming a pre-existing `IngressClass`); `target-type: ip`; healthcheck path `/health`.
- One commit. Never commit to `main`; work stays on branch `feat/gateway-waf-ingress`.
- Verification is local `kubectl kustomize` (the standalone `kustomize` binary is absent). Both `k8s/eks` and `k8s/base` must render.

---

### Task 1: WAF ALB Ingress + gateway-Service ClusterIP patch + runbook

**Files:**
- Create: `k8s/eks/waf-api-gateway-ingress.yaml`
- Modify: `k8s/eks/kustomization.yaml` (add the Ingress resource; add a JSON6902 patch for the gateway Service)
- Create: `docs/runbooks/waf-webacl.md`

**Interfaces:**
- Consumes: the base `recsys-api-gateway` Service (`k8s/base/api-gateway.yaml`, currently `type: LoadBalancer`, port 80 → targetPort `http`/8010) and Deployment.
- Produces: an EKS render where `recsys-api-gateway` is a `ClusterIP` Service (no `aws-load-balancer-*` annotations) fronted by the `recsys-api-gateway-waf` ALB Ingress with the `wafv2-acl-arn` annotation.

- [ ] **Step 1: Capture the pre-change EKS render as a baseline**

```bash
kubectl kustomize k8s/eks > /tmp/eks-before.yaml
echo "gateway Service type before:"; grep -A1 'name: recsys-api-gateway$' /tmp/eks-before.yaml | grep -c 'aws-load-balancer' || true
grep -c 'type: LoadBalancer' /tmp/eks-before.yaml
```

Expected: the render contains the gateway `Service` with `type: LoadBalancer` and `aws-load-balancer-*` annotations (baseline for comparison).

- [ ] **Step 2: Create the WAF ALB Ingress**

Create `k8s/eks/waf-api-gateway-ingress.yaml`:

```yaml
# EKS-only: WAF-protected ALB as the SOLE public entry to the API gateway.
# AWS WAF cannot attach to an NLB, so the gateway Service is switched to ClusterIP
# (see kustomization.yaml) and this ALB Ingress fronts it. The WAFv2 WebACL is
# created out-of-band (Terraform/console) and referenced by ARN — see
# docs/runbooks/waf-webacl.md. The Ingress renders with the placeholder ARN, but
# the AWS Load Balancer Controller rejects an invalid ARN at apply time.
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

- [ ] **Step 3: Wire the Ingress resource and the gateway-Service patch into the eks kustomization**

In `k8s/eks/kustomization.yaml`, add the Ingress to `resources:` (after the existing `patches/irsa-model-serving.yaml` line):

```yaml
  - waf-api-gateway-ingress.yaml   # WAF-protected ALB; sole public entry to the gateway
```

Then, at the end of the existing `patches:` list (after the `topology-aware-routing-patch.yaml` entry), add an inline JSON6902 patch that turns the gateway Service into a ClusterIP and drops its NLB annotations:

```yaml
  # Drop the NLB: the WAF ALB Ingress (waf-api-gateway-ingress.yaml) is the sole
  # public entry, so the gateway Service becomes ClusterIP and its NLB-only
  # aws-load-balancer-* annotations are removed.
  - target:
      kind: Service
      name: recsys-api-gateway
      namespace: recsys
    patch: |
      - op: replace
        path: /spec/type
        value: ClusterIP
      - op: remove
        path: /metadata/annotations
```

- [ ] **Step 4: Create the WebACL provisioning runbook**

Create `docs/runbooks/waf-webacl.md`:

```markdown
# Runbook: WAFv2 WebACL for the API Gateway ALB

The gateway is fronted by a WAF-protected ALB
(`k8s/eks/waf-api-gateway-ingress.yaml`). Kustomize cannot create the WAFv2
WebACL — provision it out-of-band and reference it by ARN.

## Prerequisites
- The **AWS Load Balancer Controller** is installed in the cluster (it provisions
  the ALB from the Ingress and attaches the WebACL).
- AWS CLI access to the account/region hosting the EKS cluster.

## 1. Create a regional WebACL

The WebACL scope MUST be `REGIONAL` (ALB), not `CLOUDFRONT`, and MUST be in the
same region as the cluster/ALB.

Recommended rules (each with action **Block**), highest priority first:
1. `AWSManagedRulesAmazonIpReputationList` (AWS managed) — known malicious IPs.
2. `AWSManagedRulesKnownBadInputsRuleSet` (AWS managed) — exploit signatures.
3. `AWSManagedRulesCommonRuleSet` (AWS managed) — OWASP-style common protections.
4. A **rate-based rule** — limit ~2000 requests / 5-minute window per source IP.

Default WebACL action: **Allow** (rules block specific traffic).

Create it from a rules JSON (managed groups + rate rule) with:

    aws wafv2 create-web-acl \
      --name recsys-api-gateway \
      --scope REGIONAL \
      --region us-east-1 \
      --default-action Allow={} \
      --visibility-config SampledRequestsEnabled=true,CloudWatchMetricsEnabled=true,MetricName=recsysApiGatewayWebAcl \
      --rules file://webacl-rules.json

Where `webacl-rules.json` contains the four rules above (three
`ManagedRuleGroupStatement`s and one `RateBasedStatement` with
`Limit: 2000, AggregateKeyType: IP`). Capture the returned `ARN`.

## 2. Wire the ARN into the Ingress

Set `alb.ingress.kubernetes.io/wafv2-acl-arn` in
`k8s/eks/waf-api-gateway-ingress.yaml` to the WebACL ARN (replacing the
`REPLACE_ME` placeholder), then apply:

    kubectl apply -k k8s/eks

The ALB Controller rejects an invalid/nonexistent ARN — the ARN must be real
before applying.

## 3. DNS cutover

Replacing the NLB with an ALB changes the public endpoint. Repoint the public DNS
record (Route 53) that pointed at the NLB to the new ALB's DNS name
(`kubectl get ingress recsys-api-gateway-waf -n recsys` shows the ALB hostname
once provisioned).

## Notes
- HTTP:80 only (parity with the prior NLB). Add an HTTPS:443 listener + ACM cert +
  HTTP→HTTPS redirect as a later hardening step.
- The WebACL region must match the ALB region, or attachment fails.
```

- [ ] **Step 5: Render and verify (this is the test)**

```bash
kubectl kustomize k8s/eks > /tmp/eks-after.yaml && echo "EKS RENDER OK"
echo "gateway Service type (expect ClusterIP, no LoadBalancer):"
grep -nE 'kind: Service' /tmp/eks-after.yaml >/dev/null && awk '/kind: Service/{s=1} s&&/name: recsys-api-gateway$/{g=1} g&&/type:/{print; g=0; s=0}' /tmp/eks-after.yaml
echo "aws-load-balancer annotations remaining (expect 0):"
grep -c 'aws-load-balancer' /tmp/eks-after.yaml
echo "WAF ingress + acl-arn present (expect >=1 each):"
grep -c 'kind: Ingress' /tmp/eks-after.yaml
grep -c 'wafv2-acl-arn' /tmp/eks-after.yaml
echo "base still renders with the NLB (unchanged):"
kubectl kustomize k8s/base | grep -c 'type: LoadBalancer'
```

Expected: `EKS RENDER OK`; the gateway Service prints `type: ClusterIP`; **0** `aws-load-balancer` occurrences in the eks render; at least one `kind: Ingress` and one `wafv2-acl-arn`; `k8s/base` still shows `type: LoadBalancer` (base unchanged). If the eks render still shows `type: LoadBalancer` or any `aws-load-balancer` annotation, the JSON6902 patch target did not match — fix the `target` name/kind/namespace and re-render.

- [ ] **Step 6: Confirm base is untouched**

```bash
git status --short k8s/base/
```

Expected: no changes under `k8s/base/`.

- [ ] **Step 7: Commit**

```bash
git add k8s/eks/waf-api-gateway-ingress.yaml k8s/eks/kustomization.yaml docs/runbooks/waf-webacl.md
git commit -m "feat(k8s): front the gateway with a WAF-protected ALB, drop the NLB

Replace the gateway NLB with a WAF-protected ALB Ingress as the sole public
entry (Service -> ClusterIP, NLB annotations removed). WebACL is referenced by
ARN and provisioned out-of-band per docs/runbooks/waf-webacl.md.

Co-Authored-By: Claude Opus 4.8 (1M context) <noreply@anthropic.com>"
```

---

## Self-Review

**Spec coverage:**
- Component 1 (gateway Service → ClusterIP, drop NLB annotations via JSON6902) → Task 1 Step 3. ✓
- Component 2 (WAF ALB Ingress `recsys-api-gateway-waf`, `ingress.class: alb`, `target-type: ip`, HTTP:80, healthcheck `/health`, `wafv2-acl-arn` placeholder, route `/`→`recsys-api-gateway:80`) → Task 1 Step 2 + kustomization resource wiring in Step 3. ✓
- Component 3 (operator runbook: regional WebACL, managed CRS/KnownBadInputs/IpReputation + rate rule, ARN wiring, DNS cutover) → Task 1 Step 4. ✓
- Verification (eks render shows ClusterIP + no aws-load-balancer + Ingress + acl-arn; base unchanged) → Task 1 Steps 5-6. ✓
- Out of scope (WebACL IaC, TLS, LB controller install) → none added. ✓

**Placeholder scan:** the full Ingress manifest, the exact kustomization additions, and the full runbook are shown; every verify step is a concrete `kubectl kustomize` command with expected output. The `REPLACE_ME` ARN and `123456789012` account are intentional operator placeholders per the spec, not plan gaps. No TBD/TODO. ✓

**Value/consistency check:** Service/Ingress names (`recsys-api-gateway` Service, `recsys-api-gateway-waf` Ingress) and the backend reference (`recsys-api-gateway:80`) are consistent between the manifest, the patch target, and the verification. The JSON6902 patch targets `kind: Service, name: recsys-api-gateway, namespace: recsys` — matching the base Service. ✓
