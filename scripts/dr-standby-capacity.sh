#!/usr/bin/env bash
# Idempotent, HPA-only preparation and read-only evidence checks for the us-west-2 DR region.
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
ACTIVE_OVERLAY="$REPO_ROOT/k8s/eks-us-west-2-active"
STANDBY_OVERLAY="$REPO_ROOT/k8s/eks-us-west-2"
BASE_OVERLAY="$REPO_ROOT/k8s/base"
PRIMARY_OVERLAY="$REPO_ROOT/k8s/eks"
DR_NAMESPACE="recsys"
DR_COMMAND="${1:-}"
DR_ORIGINAL_ARGS=("$@")
DR_CONTEXT=""
DR_REGION=""
DR_REPORT_PATH=""
DR_EVIDENCE_PATH=""
DR_DRY_RUN="false"
DR_CAPACITY_CHANGE="none"
DR_READY="false"
DR_MANIFEST_DIGEST="unknown"
DR_REPORT_WRITTEN="false"
DR_CHECK_NAMES=()
DR_CHECK_RESULTS=()
DR_CHECK_OBSERVED=()

usage() {
  cat >&2 <<EOF
Usage: $0 promote|demote --context NAME --region us-west-2 [--report FILE] [--dry-run]
       $0 cutover-check|failback-check --context NAME --region us-west-2 --evidence FILE [--report FILE]
       $0 verify [--report FILE]
EOF
  exit 2
}

add_check() {
  DR_CHECK_NAMES+=("$1")
  DR_CHECK_RESULTS+=("$2")
  DR_CHECK_OBSERVED+=("$3")
}

write_report() {
  [ -n "$DR_REPORT_PATH" ] || return 0
  local report_dir report_tmp git_commit
  report_dir="$(dirname "$DR_REPORT_PATH")"
  mkdir -p "$report_dir"
  report_tmp="$(mktemp "$report_dir/.dr-report.XXXXXX")"
  git_commit="$(git -C "$REPO_ROOT" rev-parse HEAD 2>/dev/null || printf unknown)"
  python3 - "$report_tmp" "$DR_COMMAND" "$DR_REGION" "$DR_CONTEXT" "$git_commit" \
    "$DR_MANIFEST_DIGEST" "$DR_CAPACITY_CHANGE" "$DR_READY" \
    "${#DR_CHECK_NAMES[@]}" \
    "${DR_CHECK_NAMES[@]}" --results "${DR_CHECK_RESULTS[@]}" \
    --observed "${DR_CHECK_OBSERVED[@]}" <<'PY'
import datetime, json, sys
path, command, region, context, commit, digest, change, ready, count = sys.argv[1:10]
n = int(count)
rest = sys.argv[10:]
ri = rest.index("--results")
oi = rest.index("--observed")
names = rest[:ri]
results = rest[ri + 1:oi]
observed = rest[oi + 1:]
assert len(names) == len(results) == len(observed) == n
actions = {
    "promote": [
        "promote data tier using the approved runbook",
        "run cutover-check",
        "perform operator-confirmed traffic cutover",
    ],
    "demote": ["confirm traffic has left the standby", "archive this report"],
    "cutover-check": ["perform operator-confirmed traffic cutover"],
    "failback-check": ["perform operator-confirmed traffic and data failback"],
    "verify": [],
}.get(command, ["investigate command failure"])
doc = {
    "schemaVersion": 1,
    "command": command,
    "timestamp": datetime.datetime.now(datetime.timezone.utc).isoformat().replace("+00:00", "Z"),
    "targetRegion": region,
    "context": context,
    "gitCommit": commit,
    "manifestDigest": digest,
    "checks": [
        {"name": names[i], "passed": results[i] == "true", "observed": observed[i]}
        for i in range(n)
    ],
    "capacityChange": change,
    "ready": ready == "true",
    "remainingOperatorActions": actions,
}
with open(path, "w", encoding="utf-8") as f:
    json.dump(doc, f, indent=2, ensure_ascii=False)
    f.write("\n")
PY
  mv "$report_tmp" "$DR_REPORT_PATH"
  DR_REPORT_WRITTEN="true"
}

finish() {
  local status="$1"
  trap - EXIT
  if [ "$status" -ne 0 ]; then DR_READY="false"; fi
  if [ "$DR_REPORT_WRITTEN" != "true" ]; then
    write_report || printf 'warning: could not write DR report %s\n' "$DR_REPORT_PATH" >&2
  fi
  exit "$status"
}
trap 'finish $?' EXIT

require_command() {
  command -v "$1" >/dev/null 2>&1 || {
    add_check "required-command-$1" false "not found on PATH"
    echo "Required command not found: $1" >&2
    return 1
  }
}

extract_hpas() {
  structural_render hpas
}

filter_hpas_only() {
  structural_render payload
}

validate_manifest_structure() {
  structural_render full
}

structural_render() {
  ruby -ryaml -rjson -rdigest -e '
mode=ARGV.fetch(0)
stream=Psych.parse_stream(STDIN.read)
def audit(node)
  raise "YAML aliases/anchors are forbidden" if node.is_a?(Psych::Nodes::Alias) ||
    (node.respond_to?(:anchor) && node.anchor)
  if node.is_a?(Psych::Nodes::Mapping)
    seen={}
    node.children.each_slice(2) do |key,value|
      raise "non-scalar YAML key" unless key.is_a?(Psych::Nodes::Scalar)
      raise "duplicate YAML key #{key.value}" if seen[key.value]
      seen[key.value]=true
      audit(value)
    end
  elsif node.respond_to?(:children)
    node.children.to_a.each { |child| audit(child) }
  end
end
stream.children.each { |doc| audit(doc) }
docs=stream.children.map(&:to_ruby).compact
ids={}
docs.each do |d|
  raise "resource is not a mapping" unless d.is_a?(Hash)
  api=d["apiVersion"]; kind=d["kind"]; meta=d["metadata"]
  raise "missing resource identity" unless api.is_a?(String) && kind.is_a?(String) && meta.is_a?(Hash) && meta["name"].is_a?(String)
  key=[api,kind,meta["namespace"],meta["name"]]
  raise "duplicate resource #{key.inspect}" if ids[key]
  ids[key]=true
end
hpas=docs.select { |d| d["kind"]=="HorizontalPodAutoscaler" }
expected_hpas=%w[recsys-api-gateway recsys-catalog-serving recsys-model-serving recsys-online-serving]
raise "unexpected HPA cardinality/set" unless hpas.length==4 && hpas.map{|x|x.dig("metadata","name")}.sort==expected_hpas.sort
hpas.each do |h|
  raise "invalid HPA identity" unless h["apiVersion"]=="autoscaling/v2" && h.dig("metadata","namespace")=="recsys"
  raise "invalid HPA min/max" unless h.dig("spec","minReplicas").is_a?(Integer) &&
    h.dig("spec","maxReplicas").is_a?(Integer) && h.dig("spec","minReplicas")<=h.dig("spec","maxReplicas")
end
if mode=="hpas"
  hpas.sort_by{|h|h.dig("metadata","name")}.each{|h| puts [h.dig("metadata","name"),h.dig("spec","minReplicas"),h.dig("spec","maxReplicas")].join(" ")}
elsif mode=="payload"
  canonical=nil
  canonical=lambda{|o| o.is_a?(Hash) ? o.keys.sort.to_h{|k|[k,canonical.call(o[k])]} :
    (o.is_a?(Array) ? o.map{|v|canonical.call(v)} : o)}
  puts JSON.generate(canonical.call({"apiVersion"=>"v1","kind"=>"List","items"=>hpas.sort_by{|h|h.dig("metadata","name")}}))
elsif mode=="full"
  deployments=docs.select{|d|d["kind"]=="Deployment"}
  expected=%w[recsys-api-gateway recsys-catalog-serving recsys-model-serving recsys-online-serving recsys-outbox-relay]
  raise "unexpected deployment cardinality/set" unless deployments.length==5 &&
    deployments.map{|d|d.dig("metadata","name")}.sort==expected.sort
  digests=[]
  deployments.each do |d|
    name=d.dig("metadata","name")
    podspec=d.dig("spec","template","spec")
    raise "unsupported placement constraints require validator update" if
      ["nodeSelector","affinity","tolerations"].any?{|k| podspec.key?(k) && podspec[k] && podspec[k]!={} && podspec[k]!=[]}
    containers=podspec["containers"]
    raise "invalid container cardinality" unless containers.is_a?(Array) && containers.length==1
    image=containers[0]["image"]
    match=/\A[^@\s]+@sha256:([0-9a-f]{64})\z/.match(image.to_s)
    raise "unpinned/placeholder image" unless match && match[1]!="0"*64
    digests << match[1]
    next if name=="recsys-outbox-relay"
    spreads=d.dig("spec","template","spec","topologySpreadConstraints")
    valid=spreads.is_a?(Array) && spreads.any?{|s|s["topologyKey"]=="topology.kubernetes.io/zone" && s["whenUnsatisfiable"]=="DoNotSchedule"}
    raise "missing exact topology constraint" unless valid
  end
  raise "workload digest mismatch" unless digests.uniq.length==1
  puts digests[0]
else
  raise "unknown structural render mode"
end
' "$1"
}

render_overlay() {
  kubectl kustomize "$1"
}

validate_context_region() {
  local current expected_server observed_server identity_file
  identity_file="${DR_CONTEXT_IDENTITY_FILE:-}"
  if [ -z "$identity_file" ] || [ ! -f "$identity_file" ]; then
    add_check context-region false "missing DR_CONTEXT_IDENTITY_FILE"
    return 1
  fi
  if ! expected_server="$(python3 - "$identity_file" "$DR_CONTEXT" "$DR_REGION" <<'PY'
import json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
entry=d.get("contexts",{}).get(sys.argv[2])
assert isinstance(entry,dict) and set(entry)=={"region","server"}
assert entry["region"]==sys.argv[3] and isinstance(entry["server"],str) and entry["server"].startswith("https://")
print(entry["server"])
PY
)"; then
    add_check context-region false "context=$DR_CONTEXT region=$DR_REGION"
    echo "Context '$DR_CONTEXT' has no exact authoritative region/endpoint mapping" >&2
    return 1
  fi
  current="$(kubectl config current-context)"
  if [ "$current" != "$DR_CONTEXT" ]; then
    add_check context-region false "requested=$DR_CONTEXT current=$current"
    echo "Current Kubernetes context does not match --context" >&2
    return 1
  fi
  observed_server="$(kubectl config view --raw -o json | python3 -c '
import json,sys
d=json.load(sys.stdin); target=sys.argv[1]
ctx=next((x["context"]["cluster"] for x in d["contexts"] if x["name"]==target),None)
server=next((x["cluster"]["server"] for x in d["clusters"] if x["name"]==ctx),None)
assert server
print(server)
' "$DR_CONTEXT")"
  if [ "$observed_server" != "$expected_server" ]; then
    add_check context-region false "expected endpoint=$expected_server observed=$observed_server"
    return 1
  fi
  add_check context-region true "context=$DR_CONTEXT region=$DR_REGION endpoint=$observed_server"
}

validate_image_identity() {
  local active_digest standby_digest primary_digest rendered hpa_payload
  rendered="$(render_overlay "$ACTIVE_OVERLAY")"
  hpa_payload="$(printf '%s' "$rendered" | filter_hpas_only)"
  DR_MANIFEST_DIGEST="$(printf '%s' "$hpa_payload" | shasum -a 256 | awk '{print $1}')"
  active_digest="$(printf '%s' "$rendered" | validate_manifest_structure)" || {
    add_check manifest-structure false "active overlay invalid"; return 1; }
  standby_digest="$(render_overlay "$STANDBY_OVERLAY" | validate_manifest_structure)" || {
    add_check manifest-structure false "standby overlay invalid"; return 1; }
  primary_digest="$(render_overlay "$PRIMARY_OVERLAY" | validate_manifest_structure)" || {
    add_check manifest-structure false "primary overlay invalid"; return 1; }
  if [ "$active_digest" != "$standby_digest" ] || [ "$active_digest" != "$primary_digest" ]; then
    add_check image-identity false "primary=$primary_digest active=$active_digest standby=$standby_digest"
    echo "Rendered DR overlays have a placeholder, missing, or inconsistent image identity" >&2
    return 1
  fi
  add_check manifest-structure true "exact workloads, HPAs, and topology constraints"
  add_check image-identity true "sha256:$active_digest"
}

desired_hpas() {
  render_overlay "$1" | extract_hpas
}

observed_hpas() {
  kubectl --context "$DR_CONTEXT" get hpa --namespace "$DR_NAMESPACE" \
    -o 'jsonpath={range .items[*]}{.metadata.name}{" "}{.spec.minReplicas}{" "}{.spec.maxReplicas}{"\n"}{end}' |
    python3 -c '
import sys
expected={"recsys-api-gateway","recsys-catalog-serving","recsys-model-serving","recsys-online-serving"}
out={}
for line in sys.stdin:
    parts=line.split()
    if not parts or parts[0] not in expected: continue
    if len(parts)!=3 or parts[0] in out or not all(x.isdigit() for x in parts[1:]):
        raise SystemExit("malformed or duplicate observed target HPA")
    out[parts[0]]=parts[1:]
if set(out)!=expected: raise SystemExit("missing observed target HPA")
for name in sorted(out): print(name,*out[name])
'
}

run_with_kernel_locks() {
  local lock_root lock_key operation_lock report_lock saved_report
  lock_root="${TMPDIR:-/tmp}/recsys-dr-locks"
  mkdir -p "$lock_root"
  chmod 700 "$lock_root"
  lock_key="$(printf '%s/%s' "$DR_CONTEXT" "$DR_NAMESPACE" | shasum -a 256 | awk '{print $1}')"
  operation_lock="$lock_root/operation-$lock_key.lock"
  report_lock="$lock_root/report-$(printf '%s' "${DR_REPORT_PATH:-none}" | shasum -a 256 | awk '{print $1}').lock"
  saved_report="$DR_REPORT_PATH"
  DR_REPORT_PATH=""
  trap - EXIT
  python3 - "$operation_lock" "$report_lock" "$saved_report" "$0" "${DR_ORIGINAL_ARGS[@]}" <<'PY'
import fcntl, os, signal, subprocess, sys
operation_lock, report_lock, report, script, *args = sys.argv[1:]
with open(operation_lock, "a+b") as op, open(report_lock, "a+b") as rp:
    fcntl.flock(op, fcntl.LOCK_EX)
    fcntl.flock(rp, fcntl.LOCK_EX)
    if report and os.path.exists(report):
        print("refusing to overwrite existing audit report: "+report, file=sys.stderr)
        raise SystemExit(2)
    env=os.environ.copy()
    env["DR_KERNEL_LOCK_HELD"]="1"
    child=subprocess.Popen([script, *args], env=env, start_new_session=True)
    def stop(signum, frame):
        del frame
        try: os.killpg(child.pid, signum)
        except ProcessLookupError: pass
    signal.signal(signal.SIGTERM, stop)
    signal.signal(signal.SIGINT, stop)
    raise SystemExit(child.wait())
PY
}

apply_hpas_if_needed() {
  local overlay="$1" desired observed rendered payload
  rendered="$(render_overlay "$overlay")"
  desired="$(printf '%s' "$rendered" | extract_hpas)"
  payload="$(printf '%s' "$rendered" | filter_hpas_only)"
  observed="$(observed_hpas)"
  DR_MANIFEST_DIGEST="$(printf '%s' "$payload" | shasum -a 256 | awk '{print $1}')"
  if [ "$DR_DRY_RUN" = "true" ]; then
    DR_CAPACITY_CHANGE="dry-run"
    printf '%s' "$payload" |
      kubectl --context "$DR_CONTEXT" apply --dry-run=server -f - >/dev/null || {
        add_check server-dry-run false "API validation failed"; return 1; }
    add_check server-dry-run true "HPA documents accepted"
    if [ "$desired" != "$observed" ]; then
      add_check hpa-capacity false "live differs: [$observed], desired: [$desired]"
      return 1
    fi
    add_check hpa-capacity true "live already converged"
    return 0
  fi
  if [ "$desired" = "$observed" ]; then
    DR_CAPACITY_CHANGE="none"
    add_check hpa-capacity true "already converged: $desired"
    return 0
  fi
  DR_CAPACITY_CHANGE="attempted"
  if ! printf '%s' "$payload" |
      kubectl --context "$DR_CONTEXT" apply -f - >/dev/null; then
    add_check hpa-apply false "apply failed"; return 1
  fi
  observed="$(observed_hpas)"
  if [ "$desired" != "$observed" ]; then
    add_check hpa-convergence false "post-apply live=[$observed], desired=[$desired]"
    return 1
  fi
  DR_CAPACITY_CHANGE="applied"
  add_check hpa-convergence true "$observed"
}

check_rollout() {
  if kubectl --context "$DR_CONTEXT" rollout status deployment --all \
      --namespace "$DR_NAMESPACE" --timeout=5m >/dev/null; then
    add_check rollout true "all deployments rolled out"
  else
    add_check rollout false "rollout status failed or timed out"
    return 1
  fi
}

check_ready_replicas() {
  if kubectl --context "$DR_CONTEXT" wait pod --all --namespace "$DR_NAMESPACE" \
      --for=condition=Ready --timeout=5m >/dev/null; then
    add_check ready-replicas true "all pods Ready"
  else
    add_check ready-replicas false "pod readiness failed or timed out"
    return 1
  fi
}

check_pdbs() {
  local evidence result
  evidence="$(kubectl --context "$DR_CONTEXT" get pdb --namespace "$DR_NAMESPACE" \
    -o 'jsonpath={range .items[*]}{.metadata.name}{" "}{.status.disruptionsAllowed}{" "}{.status.currentHealthy}{" "}{.status.desiredHealthy}{"\n"}{end}')"
  if ! result="$(python3 -c '
import sys
command=sys.argv[1]
expected={"recsys-api-gateway-pdb","recsys-catalog-serving-pdb",
          "recsys-model-serving-pdb","recsys-online-serving-pdb"}
seen={}
for line in sys.stdin:
    p=line.split()
    if len(p)!=4 or p[0] in seen or not all(x.isdigit() for x in p[1:]):
        raise SystemExit("malformed or duplicate PDB evidence")
    seen[p[0]]=tuple(map(int,p[1:]))
if set(seen)!=expected: raise SystemExit("unexpected PDB set")
for name,(allowed,current,desired) in seen.items():
    if current<desired or (command=="promote" and allowed<1):
        raise SystemExit("unhealthy PDB "+name)
print("exact PDB set healthy")
' "$DR_COMMAND" <<<"$evidence")"; then
    add_check pdb-health false "${evidence:-no PDB evidence}"
    return 1
  fi
  add_check pdb-health true "$result: $evidence"
}

check_dependencies() {
  local evidence="${DR_DEPENDENCY_EVIDENCE_FILE:-}" result
  if [ -z "$evidence" ] || [ ! -f "$evidence" ]; then
    add_check dependencies false "missing DR_DEPENDENCY_EVIDENCE_FILE"
    return 1
  fi
  if ! result="$(python3 - "$evidence" "$DR_MANIFEST_DIGEST" <<'PY'
import datetime,json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
assert set(d)=={"schemaVersion","source","provenance","observedAt","status","manifestDigests"}
assert d["schemaVersion"]==1
assert d["source"]=="recsys-dependency-observer/v1"
assert d["provenance"]=="approved-read-only-dependency-probes"
t=datetime.datetime.fromisoformat(d["observedAt"].replace("Z","+00:00"))
now=datetime.datetime.now(datetime.timezone.utc)
assert t.tzinfo and datetime.timedelta(0)<=now-t<=datetime.timedelta(minutes=15)
assert d["status"]=="healthy"
assert isinstance(d["manifestDigests"],list) and sys.argv[2] in d["manifestDigests"]
print(d["source"]+" "+d["provenance"]+" healthy")
PY
)"; then
    add_check dependencies false "invalid, stale, unhealthy, or wrong-digest dependency evidence"
    return 1
  fi
  add_check dependencies true "$result"
}

check_services() {
  local service port path result
  for service in api-gateway catalog-serving model-serving online-serving; do
    case "$service" in
      api-gateway) port=80; path=/health ;;
      catalog-serving) port=6010; path=/health/ready ;;
      model-serving) port=8080; path=/health/ready ;;
      online-serving) port=7010; path=/health/ready ;;
    esac
    if ! result="$(kubectl --context "$DR_CONTEXT" get --raw \
      "/api/v1/namespaces/$DR_NAMESPACE/services/recsys-$service:$port/proxy$path" \
      --request-timeout=10s)"; then
      add_check "service-$service" false "probe failed"; return 1
    fi
    if ! python3 -c 'import json,sys; assert json.load(sys.stdin).get("status")=="UP"' <<<"$result"; then
      add_check "service-$service" false "$result"; return 1
    fi
    add_check "service-$service" true "$result"
  done
}

check_topology_scheduling() {
  local nodes_file pods_file result
  nodes_file="$(mktemp "${TMPDIR:-/tmp}/dr-nodes.XXXXXX")"
  pods_file="$(mktemp "${TMPDIR:-/tmp}/dr-pods.XXXXXX")"
  kubectl --context "$DR_CONTEXT" get nodes -o json >"$nodes_file"
  kubectl --context "$DR_CONTEXT" get pods --namespace "$DR_NAMESPACE" \
    -l 'app in (recsys-api-gateway,recsys-catalog-serving,recsys-model-serving,recsys-online-serving)' \
    -o json >"$pods_file"
  if ! result="$(python3 - "$nodes_file" "$pods_file" "$DR_COMMAND" <<'PY'
import json,sys
nodes=json.load(open(sys.argv[1]))["items"]; pods=json.load(open(sys.argv[2]))["items"]
usable={}
for n in nodes:
    ready=any(c.get("type")=="Ready" and c.get("status")=="True" for c in n.get("status",{}).get("conditions",[]))
    tainted=any(t.get("effect") in ("NoSchedule","NoExecute") for t in n.get("spec",{}).get("taints",[]))
    zone=n.get("metadata",{}).get("labels",{}).get("topology.kubernetes.io/zone")
    if ready and not n.get("spec",{}).get("unschedulable",False) and not tainted and zone:
        usable[n["metadata"]["name"]]=zone
if len(set(usable.values()))<2: raise SystemExit("fewer than two ready untainted schedulable zones")
minimum={"recsys-api-gateway":2,"recsys-catalog-serving":2,"recsys-model-serving":3,"recsys-online-serving":2}
if sys.argv[3]=="demote": minimum={"recsys-api-gateway":1,"recsys-catalog-serving":1,"recsys-model-serving":2,"recsys-online-serving":1}
placed={k:[] for k in minimum}
for p in pods:
    app=p.get("metadata",{}).get("labels",{}).get("app")
    node=p.get("spec",{}).get("nodeName")
    ready=any(c.get("type")=="Ready" and c.get("status")=="True" for c in p.get("status",{}).get("conditions",[]))
    if app in placed and ready and node in usable: placed[app].append(usable[node])
for app,zones in placed.items():
    required_zones=min(2,minimum[app])
    if len(zones)<minimum[app] or len(set(zones))<required_zones:
        raise SystemExit(app+" lacks ready cross-zone target capacity")
print("ready nodes and target pods span zones: "+",".join(sorted(set(usable.values()))))
PY
)"; then
    rm -f "$nodes_file" "$pods_file"
    add_check topology-schedulability false "node/pod placement mismatch"
    return 1
  fi
  rm -f "$nodes_file" "$pods_file"
  add_check topology-schedulability true "$result"
}

verify_drift() {
  local active base
  active="$(render_overlay "$ACTIVE_OVERLAY" | extract_hpas)"
  base="$(render_overlay "$BASE_OVERLAY" | extract_hpas)"
  DR_MANIFEST_DIGEST="$(render_overlay "$ACTIVE_OVERLAY" | shasum -a 256 | awk '{print $1}')"
  if [ "$active" != "$base" ]; then
    add_check overlay-drift false "active=[$active] base=[$base]"
    echo "DRIFT: active overlay minReplicas differ from base" >&2
    return 1
  fi
  add_check overlay-drift true "$active"
  DR_READY="true"
  echo "OK: active overlay minReplicas match the primary baseline:"
  printf '%s\n' "$active"
}

check_operator_evidence() {
  local parsed writer direction lag traffic health evidence_digest
  local expected_writer expected_direction expected_traffic
  if [ -z "$DR_EVIDENCE_PATH" ] || [ ! -f "$DR_EVIDENCE_PATH" ]; then
    add_check operator-evidence false "missing --evidence JSON file"; return 1
  fi
  if ! parsed="$(python3 - "$DR_EVIDENCE_PATH" <<'PY'
import datetime,json,sys
d=json.load(open(sys.argv[1],encoding="utf-8"))
required={"source","observedAt","writerIdentity","replicationDirection","lagStatus",
          "rpoAccepted","trafficTarget","health","manifestDigest"}
if set(d)!=required or not isinstance(d["source"],str) or not d["source"].strip():
    raise SystemExit("malformed evidence schema/source")
t=datetime.datetime.fromisoformat(d["observedAt"].replace("Z","+00:00"))
now=datetime.datetime.now(datetime.timezone.utc)
if t.tzinfo is None or not (datetime.timedelta(0) <= now-t <= datetime.timedelta(minutes=15)):
    raise SystemExit("stale or future evidence")
if d["rpoAccepted"] is not True: raise SystemExit("RPO not accepted")
for k in ("writerIdentity","replicationDirection","lagStatus","trafficTarget","health","manifestDigest"):
    if not isinstance(d[k],str) or not d[k]: raise SystemExit("invalid "+k)
print("\t".join(d[k] for k in ("writerIdentity","replicationDirection","lagStatus","trafficTarget","health","manifestDigest")))
PY
)"; then
    add_check operator-evidence false "malformed, stale, future, or incomplete"; return 1
  fi
  IFS=$'\t' read -r writer direction lag traffic health evidence_digest <<<"$parsed"
  if [ "$DR_COMMAND" = "cutover-check" ]; then
    expected_writer="us-west-2"; expected_direction="west-to-east"; expected_traffic="us-west-2"
  else
    expected_writer="us-east-1"; expected_direction="east-to-west"; expected_traffic="us-east-1"
  fi
  if [ "$writer" != "$expected_writer" ]; then
    add_check writer-identity false "$writer (expected $expected_writer)"
    return 1
  fi
  add_check writer-identity true "$writer"
  if [ "$direction" != "$expected_direction" ]; then
    add_check replication-direction false "$direction (expected $expected_direction)"
    return 1
  fi
  add_check replication-direction true "$direction"
  if [ "$lag" != "accepted" ]; then
    add_check replication-lag false "$lag"
    return 1
  fi
  add_check replication-lag true "$lag"
  if [ "$traffic" != "$expected_traffic" ]; then
    add_check traffic-target false "$traffic (expected $expected_traffic)"
    return 1
  fi
  add_check traffic-target true "$traffic"
  if [ "$health" != "healthy" ]; then
    add_check evidence-health false "$health"
    return 1
  fi
  add_check evidence-health true "$health"
  if [ "$evidence_digest" != "$DR_MANIFEST_DIGEST" ]; then
    add_check evidence-manifest-digest false "$evidence_digest"; return 1
  fi
  add_check evidence-manifest-digest true "$evidence_digest"
  if [ "$(desired_hpas "$ACTIVE_OVERLAY")" != "$(observed_hpas)" ]; then
    add_check application-capacity false "live HPA min/max differ from active overlay"; return 1
  fi
  add_check application-capacity true "independently observed exact live min/max"
  check_topology_scheduling
  check_services
  check_dependencies
}

[ -n "$DR_COMMAND" ] || usage
shift || true
while [ $# -gt 0 ]; do
  case "$1" in
    --context) [ $# -ge 2 ] || usage; DR_CONTEXT="$2"; shift 2 ;;
    --region) [ $# -ge 2 ] || usage; DR_REGION="$2"; shift 2 ;;
    --report) [ $# -ge 2 ] || usage; DR_REPORT_PATH="$2"; shift 2 ;;
    --evidence) [ $# -ge 2 ] || usage; DR_EVIDENCE_PATH="$2"; shift 2 ;;
    --dry-run) DR_DRY_RUN="true"; shift ;;
    -h|--help) usage ;;
    *) echo "Unknown argument: $1" >&2; usage ;;
  esac
done

require_command kubectl
require_command python3

if [ "$DR_COMMAND" != verify ] && [ "${DR_KERNEL_LOCK_HELD:-0}" != 1 ]; then
  run_with_kernel_locks
  exit $?
fi

case "$DR_COMMAND" in
  verify)
    [ -z "$DR_CONTEXT" ] || { echo "verify is offline and does not accept --context" >&2; exit 2; }
    verify_drift
    ;;
  promote|demote)
    [ -n "$DR_CONTEXT" ] && [ -n "$DR_REGION" ] || usage
    validate_context_region
    validate_image_identity
    if [ "$DR_COMMAND" = "promote" ]; then overlay="$ACTIVE_OVERLAY"; else overlay="$STANDBY_OVERLAY"; fi
    apply_hpas_if_needed "$overlay"
    check_rollout
    check_ready_replicas
    check_pdbs
    check_topology_scheduling
    check_services
    check_dependencies
    [ "$(desired_hpas "$overlay")" = "$(observed_hpas)" ] || {
      add_check final-hpa-convergence false "capacity changed during readiness checks"; exit 1; }
    add_check final-hpa-convergence true "exact desired min/max retained"
    DR_READY="true"
    ;;
  cutover-check|failback-check)
    [ -n "$DR_CONTEXT" ] && [ -n "$DR_REGION" ] || usage
    [ "$DR_DRY_RUN" = "false" ] || { echo "--dry-run is not meaningful for read-only checks" >&2; exit 2; }
    validate_context_region
    validate_image_identity
    check_operator_evidence
    if [ "$(desired_hpas "$ACTIVE_OVERLAY")" != "$(observed_hpas)" ]; then
      add_check final-active-capacity false "active min/max changed during prerequisite checks"
      exit 1
    fi
    add_check final-active-capacity true "exact active min/max re-observed as final gate"
    DR_READY="true"
    ;;
  -h|--help) usage ;;
  *) echo "Unknown command: $DR_COMMAND" >&2; usage ;;
esac

write_report
