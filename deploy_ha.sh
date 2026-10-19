#!/usr/bin/env bash
# Deploy the private Event Processor custom component to the HA container.
set -euo pipefail

REMOTE="${REMOTE:-acer}"
REMOTE_TMP="${REMOTE_TMP:-/tmp/event_processor_component}"
HA_CONFIG="${HA_CONFIG:-/home/tai/home-assistant/config}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
COMPONENT_DIR="${SCRIPT_DIR}/custom_components/event_processor"

if [[ ! -d "${COMPONENT_DIR}" ]]; then
  echo "Component directory not found: ${COMPONENT_DIR}" >&2
  exit 1
fi

echo "Copying event_processor component to ${REMOTE}:${REMOTE_TMP}"
ssh "${REMOTE}" "rm -rf '${REMOTE_TMP}' && mkdir -p '${REMOTE_TMP}'"
tar -C "${SCRIPT_DIR}/custom_components" -cf - event_processor \
  | ssh "${REMOTE}" "tar -C '${REMOTE_TMP}' -xf -"

echo "Installing component into ${HA_CONFIG}/custom_components"
ssh "${REMOTE}" "mkdir -p '${HA_CONFIG}/custom_components' \
  && rm -rf '${HA_CONFIG}/custom_components/event_processor' \
  && cp -a '${REMOTE_TMP}/event_processor' '${HA_CONFIG}/custom_components/event_processor'"

echo "Restarting Home Assistant"
ssh "${REMOTE}" "docker restart homeassistant"

echo "Waiting for Home Assistant to come back"
for _ in $(seq 1 30); do
  if ssh "${REMOTE}" "curl -fsS -o /dev/null http://127.0.0.1:8123/"; then
    echo "Home Assistant is reachable"
    break
  fi
  sleep 2
done

echo "Recent Event Processor log lines:"
ssh "${REMOTE}" "docker logs --tail 200 homeassistant 2>&1 | grep -i event_processor || true"
