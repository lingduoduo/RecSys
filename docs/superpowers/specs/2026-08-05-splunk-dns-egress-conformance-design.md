# Splunk and DNS egress conformance — design

[The egress conformance work](2026-08-05-networkpolicy-egress-conformance-design.md) closed six
gaps in `k8s/base/network-policy.yaml` and added `NetworkPolicyEgressManifestTest` to keep the set
of permitted destinations aligned with the set the services actually dial. It aligned those two
sets against `recsys-config`.

That is not the whole set. Two destinations sit outside it, and one of them is outside it in a way
the test cannot currently see.

## The problem

### 1. Every service dials Splunk, and no rule permits it

`SPLUNK_HEC_URL` is a literal `value:` entry in four Deployments — not a `recsys-config` key:

| Workload | Declaration |
|---|---|
| `recsys-api-gateway` | `k8s/base/api-gateway.yaml` |
| `recsys-catalog-serving` | `k8s/base/catalog-serving.yaml` |
| `recsys-model-serving` | `k8s/base/model-serving.yaml` |
| `recsys-online-serving` | `k8s/base/online-serving.yaml` |

All four carry `http://splunk:8088/services/collector/event`. No egress rule permits 8088, no
`splunk` Service exists in `k8s/`, and no NetworkPolicy governs such a pod.

The failure mode is the worst available. The appender is bounded, drop-on-full, and **at-most-once
by design** — a blocked connection is indistinguishable from a healthy service that logged
nothing. `splunk_hec_*` counters would show it, but only to someone already looking. This is the
same silent class as the service-registry gap the previous design called out, and it survived that
design.

The reason it survived is structural, and matters more than the gap itself.
`NetworkPolicyEgressManifestTest` derives addresses from `recsys-config` alone. Assertion (4) —
"every ConfigMap upstream key is claimed", the drift catcher — cannot claim a key that is not in
the ConfigMap. `SPLUNK_HEC_URL` matches `_URL`, one of the suffixes it scans for, and is invisible
anyway. **Any future upstream declared as a Deployment env var reproduces this exactly**, which is
why the fix below widens the derivation rather than adding one key.

`recsys-outbox-relay` declares no `SPLUNK_*` env and ships no logs to Splunk. It is also
`policyTypes: [Ingress]`, so it needs nothing here either way.

### 2. DNS egress permits any destination

Each of the four Egress-restricted policies ends with:

```yaml
    - ports:
        - port: 53
          protocol: UDP
        - port: 53
          protocol: TCP
```

A rule with no `to[]` permits the port to **every** destination. So every workload whose other
outbound traffic is confined to a hand-checked allow-list may still send arbitrary UDP or TCP to
port 53 anywhere, in-cluster or on the internet. That is a tunnel, and it is the one channel the
allow-list leaves open at exactly the point the rest of it is tight.

## Manifest changes

In `k8s/base/network-policy.yaml`, applied to all four Egress-restricted policies:

1. **Add the Splunk rule** — `podSelector: app: splunk` on 8088.

   The destination is operator-supplied: `docs/runbooks/splunk-hec-logging.md` documents that no
   Splunk is deployed by these manifests and that the operator either creates a Service literally
   named `splunk` or repoints `SPLUNK_HEC_URL`. The rule matches the first case, which is the
   default the manifests already carry. The second case is handled by the test rather than by a
   speculative rule: with the widened derivation, repointing `SPLUNK_HEC_URL` fails the build until
   a matching rule exists. No `ipBlock` patch ships for an off-cluster HEC endpoint, because none
   exists in any region — a `REPLACE_ME` rule nobody fills in reads as coverage without being any.

2. **Scope the DNS rule** — the same ports under
   `to: namespaceSelector: kubernetes.io/metadata.name=kube-system`.

   Namespace, deliberately not `podSelector: k8s-app=kube-dns` on top of it. A cluster running
   NodeLocal DNSCache resolves through a host-networked DaemonSet that a `podSelector` may not
   match, and CoreDNS labelling varies. The failure mode of over-tightening is every DNS lookup
   dropped — total outage — on a control nobody has confirmed the cluster enforces. One namespace
   of cluster infrastructure is not a meaningful exfiltration path; "anywhere on the internet" is.

## Test changes

`NetworkPolicyEgressManifestTest` keeps its structure. Two things change.

### Source of truth

The derivation becomes the **union** of two sources:

- `recsys-config` keys, owned via the declared `OWNED_KEYS` map, exactly as today.
- Every `k8s/base` Deployment's inline `env:` entries that carry a literal `value:`, owned
  **implicitly by the Deployment that declares them**.

The asymmetry is the point. `recsys-config` is `envFrom`'d into five workloads, so a ConfigMap key
says nothing about who dials it and ownership has to be declared. A Deployment env var names its
own dialer, so ownership is derived and cannot drift — no `OWNED_KEYS` entry is required or
accepted for it.

Keys whose value is blank are skipped in both sources: an empty string names no destination.
`ONLINE_EVENTS_SQS_QUEUE_URL: ""` in `online-serving.yaml` is the case that makes this load-bearing
— without the skip it would demand an egress rule to a queue nobody dials.

Host-to-label resolution is unchanged and stays strict. `EXTERNALLY_DEPLOYED` gains `splunk`,
joining `ollama`, `mysql`, and `kafka` as a host `k8s/base` names but does not deploy.

### Assertions

The five existing assertions keep their meaning; two change in reach:

- **(1) Every declared upstream is permitted.** Now covers `SPLUNK_HEC_URL`, and so requires the
  `app: splunk` / 8088 rule in all four policies. Selector and port must still sit in the same
  rule.
- **(4) Every upstream key is claimed.** Extends to Deployment env keys, which satisfy it by being
  declared on a Deployment rather than by an `OWNED_KEYS` entry.
- **(3) Egress and ingress agree** skips Splunk: no base NetworkPolicy governs a pod that
  `k8s/base` does not deploy, the same as ollama, mysql, and kafka today.

One assertion is added:

- **(7) DNS egress is destination-scoped.** For every Egress-restricted policy, any rule listing
  port 53 must carry a non-empty `to[]`. Without this, the scoping silently reverts to a bare
  `ports: [53]` the first time someone simplifies the file, and no other assertion would notice —
  a rule that is *too permissive* passes every check that asks whether a destination is reachable.

Failure messages follow house style: name the unreachable endpoint, the policy missing the rule,
and the runtime symptom. The Splunk message must say that delivery is at-most-once and drop-on-full,
because "logs are missing from Splunk" and "logs were never sent" send an operator to different
places.

## Documentation

`docs/system_design/20_AuthN_AuthZ.md`:

- §8 gains the two rules — the Splunk destination and the DNS scoping, with the NodeLocal DNSCache
  reasoning attached, since the looser-than-ideal selector is a decision someone will otherwise
  read as an oversight.
- **Sharp edge 9 needs rewording.** It currently reads "a destination absent from the ConfigMap is
  a destination it cannot know about", which stops being true once the test reads Deployment env.
  The accurate statement is: absent from the ConfigMap *and* from every Deployment's inline env.
  Its list of unpermitted 443 consumers also gains PostHog (below).

`docs/runbooks/splunk-hec-logging.md`: the "repoint `SPLUNK_HEC_URL` at a real collector" procedure
gains a step — a new destination needs a matching egress rule, and the build fails until it has
one. That is the intended workflow, not an obstacle to route around.

## Testing and CI

`NetworkPolicyEgressManifestTest` is already in the `<includes>` of the `resilience` profile
(`pom.xml`), which is what the PR gate runs. It stays pure file parsing — no Redis, no containers,
no cluster — so no CI change is needed.

Each manifest change is verified by the test failing before it and passing after:

| Manifest change | Covered by |
|---|---|
| Splunk egress rule ×4 | (1) upstream permitted, via Deployment-env derivation |
| DNS `to:` scoping ×4 | (7) DNS egress is destination-scoped |

## Out of scope

Recorded so that a green conformance run is not mistaken for a closed problem. Each was found while
investigating the two gaps above; none is addressed here.

- **Redis and Sentinel have unrestricted egress.** Both policies declare `policyTypes: [Ingress]`
  only. Those pods hold the entire keyspace — user and item embeddings, `sr:*` history, and per
  sharp edge 3 of `20_AuthN_AuthZ` the plaintext API key at `login:<token>`.
- **The reconciliation CronJob is matched by no policy at all.** `recsys-outbox-reconciliation`
  carries a label no NetworkPolicy selects, so it has unrestricted ingress *and* egress while
  mounting both `recsys-config` and `recsys-secrets`.
- **No namespace default-deny.** The policy set is an allow-list over seven label sets; anything
  else in the namespace gets Kubernetes' permissive default. Deferred because a default-deny
  changes the posture of pods this repo does not deploy, and is unverifiable while CNI enforcement
  is unconfirmed.
- **PostHog egress to a third party.** `PostHogFeatureFlagProvider` POSTs `distinct_id` and
  `person_properties` to `https://us.i.posthog.com` by default. Disabled by default and absent from
  `k8s/`, but it is the only path that sends user identifiers to an external SaaS, and sharp edge 9
  enumerates the 443 consumers without it.
- **No caller-to-subject binding.** The gateway injects `x-authenticated-subject` and no backend
  reads it, so any authenticated caller can request any `userId`. This is the data-plane half of
  sharp edge 1, which currently frames the gap in control-plane terms.
- **`LlmResponseCache` keys on the request-body hash alone** while the proxy forwards identity
  headers upstream. Harmless against ollama, which ignores them; it becomes cross-user response
  bleed if an LLM upstream ever personalizes on the identity it is handed.
- **Overlay rendering in CI**, unchanged from the previous design: overlay env resolution needs
  `kustomize` in the test path, which no test requires.
