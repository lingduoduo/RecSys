# Local Splunk runbook design

## Goal

Make `docs/runbooks/splunk-hec-logging.md` a copy-pasteable guide for running and
checking the repository's local Splunk HEC integration, especially on an Apple
Silicon Mac using Colima.

## Scope

Rewrite the local bring-up material while preserving the existing delivery contract,
EKS operations, tuning, and production-divergence sections. The local guide will cover:

- detecting host architecture and verifying the Docker daemon/context;
- using a separate Colima profile with VZ and Rosetta on Apple Silicon;
- generating credentials once in a permission-restricted temporary env file;
- consistently passing that env file to every Compose command;
- waiting for Splunk and `splunk-init`, without removing unrelated Compose orphans;
- opening the web UI and retrieving the password used by the running container;
- exporting the matching HEC token before starting the four local services;
- checking HEC health, sending a direct HEC test event, and finding it in Splunk;
- recovering both the password and HEC token from the running container when a
  regenerated env file no longer matches the initialized Splunk volumes;
- starting streaming infrastructure separately from the host JVM services;
- setting and validating the cursor signing key and local anonymous gateway mode;
- detecting stale listeners when one service port appears healthy after a failed start;
- monitoring service logs and checking all four health endpoints; and
- using practical Splunk searches for traffic, service counts, warnings, errors, and
  exceptions;
- diagnosing variable interpolation, missing Colima sockets, slow initialization,
  misleading container health, unavailable port 8000, and login mismatch;
- stopping the stack, optionally deleting its volumes, and verifying cleanup; and
- the reliable x86_64 and CI alternatives when ARM emulation fails.

Add one contextual entry point in `README.md` under **Start the
artifact-dependent full stack**. It will tell contributors that structured Splunk
logging is optional and link to `docs/runbooks/splunk-hec-logging.md` for local
collector, UI, credential, and HEC verification setup. Keep the existing link in the
complete operational-runbook index; do not duplicate the runbook's commands in the
README or add a separate top-level Splunk section.

## Structure

The existing `Local bring-up` section becomes a local-first sequence:

1. State the support boundary: genuine x86_64 is reliable; Apple Silicon with Rosetta
   is a best-effort development path and can still hit Splunk/KVStore crashes.
2. Provide separate prerequisites for ordinary x86_64 Docker and Apple Silicon Colima.
3. Use `/tmp/recsys-splunk.env` as the canonical local credential source.
4. Start Compose, monitor readiness, log into the UI, validate HEC health and ingest a
   manual event.
5. Start the streaming dependencies, prepare the required host-service environment,
   check for stale listeners, start the JVM services, and validate all four health
   endpoints.
6. Search for service logs using copy-pasteable operational queries.
7. Follow with symptom-oriented troubleshooting and cleanup commands.

Commands that display or recover a credential will say so explicitly. Commands that
delete named volumes will state that indexed local data and configuration are lost.
`--remove-orphans` will not be recommended because the reported orphan containers belong
to other repository Compose stacks.

If `/tmp/recsys-splunk.env` is accidentally regenerated while the Splunk volumes remain,
the running container's environment is the recovery source for both local credentials.
The runbook will overwrite the env file with those recovered values using `umask 077`,
then validate the collector before starting the applications. This recovery is for the
local disposable stack only and will be clearly marked as exposing credentials to the
current shell.

The application workflow will not treat one successful health response as proof that the
startup script worked. Before launch it will identify listeners on ports 6010, 7010,
8080, and 8010; after launch it will check all four endpoints and point readers to the
four `logs/*.log` files. This distinguishes a stale RecSys Serving process from a complete
four-service startup.

## Verification

- Check every documented Compose command that parses `docker-compose.splunk.yml` either
  uses `--env-file /tmp/recsys-splunk.env` or supplies placeholders for cleanup.
- Check shell examples for consistent context/profile names and line continuations.
- Check the recovered-token workflow with HEC's health endpoint and a direct collector
  POST before application startup.
- Check that startup instructions cover `docker-compose.streaming.yml`,
  `RECOMMENDATION_CURSOR_SIGNING_KEY`, `GATEWAY_ALLOW_ANONYMOUS`, all four listening
  ports, all four health endpoints, and all four local log files.
- Check searches use the configured `index=recsys` and
  `sourcetype="recsys:app:log"`, and state that only model serving currently populates
  `traceId`.
- Run `git diff --check`.
- Review the rendered Markdown structure and links around the edited section.
- Run `DocumentationIndexTest` so the new README link and the retained runbook-index
  link are both covered by the repository's documentation contract.
