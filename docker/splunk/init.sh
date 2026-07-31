#!/usr/bin/env sh
# Idempotent HEC provisioning for the local Splunk stand-in.
#
# Runs once after splunkd reports healthy. Re-running against existing volumes is a no-op,
# so `docker compose up` after a restart does not drift.
#
# Splunk's management API is always HTTPS with a self-signed cert, hence -k throughout.
# That is the MANAGEMENT port (8089). The HEC port (8088) is what we force to plain HTTP.
set -eu

MGMT="https://splunk:8089"
AUTH="admin:${SPLUNK_PASSWORD}"
INDEX="${SPLUNK_HEC_INDEX:-recsys}"
TOKEN="${SPLUNK_HEC_TOKEN}"

echo "==> Creating index '${INDEX}' (409 = already exists, fine)"
curl -kfsS -u "$AUTH" "${MGMT}/services/data/indexes" \
  -d "name=${INDEX}" -o /dev/null -w '%{http_code}\n' || true

echo "==> Enabling HEC globally with SSL off"
curl -kfsS -u "$AUTH" \
  "${MGMT}/servicesNS/nobody/splunk_httpinput/data/inputs/http/http" \
  -d disabled=0 -d enableSSL=0 -o /dev/null || true

echo "==> Creating HEC token 'recsys'"
curl -kfsS -u "$AUTH" \
  "${MGMT}/servicesNS/nobody/splunk_httpinput/data/inputs/http" \
  -d "name=recsys" -d "token=${TOKEN}" -d "index=${INDEX}" -d "indexes=${INDEX}" \
  -o /dev/null || true

echo "==> Verifying plain-HTTP HEC delivery"
i=0
while [ "$i" -lt 30 ]; do
  code=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Splunk ${TOKEN}" \
    -H 'Content-Type: application/json' \
    --data '{"event":{"message":"splunk-init verification"},"sourcetype":"recsys:app:log"}' \
    "http://splunk:8088/services/collector/event" || true)
  if [ "$code" = "200" ]; then
    echo "==> HEC is accepting events over plain HTTP. Ready."
    exit 0
  fi
  echo "    not ready yet (HTTP ${code}); retrying..."
  i=$((i + 1))
  sleep 5
done

echo "!!! HEC did not accept a plain-HTTP event after 150s." >&2
echo "!!! Check: docker compose -f docker-compose.splunk.yml logs splunk" >&2
exit 1
