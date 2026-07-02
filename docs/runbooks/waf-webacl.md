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
