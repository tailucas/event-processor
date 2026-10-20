#!/usr/bin/env bash
# Deploy the hub API and Java event publisher to the production host, rebuild
# the container image, and recreate the event-processor service.
set -euo pipefail

REMOTE="${REMOTE:-acer}"
REMOTE_DIR="${REMOTE_DIR:-/home/tai/event-processor}"
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

FILES=(
  app/__main__.py
  app/hub_api.py
  src/main/java/tailucas/app/EventProcessor.java
  src/main/java/tailucas/app/device/Event.java
  src/main/java/tailucas/app/device/Meter.java
  src/main/java/tailucas/app/provider/HubEventPublisher.java
  tests/test_hub_api.py
  custom_components/event_processor
  docker-compose.yml
  deploy_ha.sh
  deploy_hub.sh
)

echo "Copying hub changes to ${REMOTE}:${REMOTE_DIR}"
tar -C "${SCRIPT_DIR}" -cf - "${FILES[@]}" \
  | ssh "${REMOTE}" "tar -C '${REMOTE_DIR}' -xf -"

if [[ "${SKIP_BUILD:-0}" != "1" ]]; then
  echo "Building the event processor image on ${REMOTE}"
  ssh "${REMOTE}" "cd '${REMOTE_DIR}' && make build"
fi

echo "Recreating the event processor service"
ssh "${REMOTE}" "bash -s" <<EOF
set -e
eval "\$(grep -E '^export OP_(CONNECT_HOST|CONNECT_TOKEN|VAULT)=' ~/.bashrc)"
cd '${REMOTE_DIR}'
docker compose up -d --remove-orphans
EOF

echo "Waiting for the hub API"
for _ in $(seq 1 30); do
  if ssh "${REMOTE}" "docker exec event-processor-app-1 sh -c 'wget -q -O - http://127.0.0.1:8080/api/ping'"; then
    echo "Hub API is reachable"
    break
  fi
  sleep 2
done

echo "Recent event processor log lines:"
ssh "${REMOTE}" "docker logs --tail 100 event-processor-app-1 2>&1 || true"
