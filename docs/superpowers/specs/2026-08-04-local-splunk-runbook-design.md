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
- sending a direct HEC test event and finding it in Splunk;
- diagnosing variable interpolation, missing Colima sockets, slow initialization,
  misleading container health, unavailable port 8000, and login mismatch;
- stopping the stack, optionally deleting its volumes, and verifying cleanup; and
- the reliable x86_64 and CI alternatives when ARM emulation fails.

## Structure

The existing `Local bring-up` section becomes a local-first sequence:

1. State the support boundary: genuine x86_64 is reliable; Apple Silicon with Rosetta
   is a best-effort development path and can still hit Splunk/KVStore crashes.
2. Provide separate prerequisites for ordinary x86_64 Docker and Apple Silicon Colima.
3. Use `/tmp/recsys-splunk.env` as the canonical local credential source.
4. Start Compose, monitor readiness, log into the UI, load credentials into the host
   shell, run services, generate traffic, and search for it.
5. Follow with symptom-oriented troubleshooting and cleanup commands.

Commands that display or recover a credential will say so explicitly. Commands that
delete named volumes will state that indexed local data and configuration are lost.
`--remove-orphans` will not be recommended because the reported orphan containers belong
to other repository Compose stacks.

## Verification

- Check every documented Compose command that parses `docker-compose.splunk.yml` either
  uses `--env-file /tmp/recsys-splunk.env` or supplies placeholders for cleanup.
- Check shell examples for consistent context/profile names and line continuations.
- Run `git diff --check`.
- Review the rendered Markdown structure and links around the edited section.

