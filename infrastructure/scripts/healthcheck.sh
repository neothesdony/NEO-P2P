#!/bin/sh
# NEO-P2P health checker — pings all services every 60s.
# Mounted into the healthchecker container (see docker-compose*.yml).
set -e

apk add --no-cache curl jq >/dev/null 2>&1 || true

while true; do
  for svc in strfry-1:8080 strfry-2:8080 strfry-3:8080 strfry-meta:8080 libp2p-relay:4002 ws-relay:4003 coturn:3478; do
    host=${svc%:*}
    port=${svc#*:}
    if curl -sf "http://${host}:${port}" >/dev/null 2>&1; then
      echo "$(date -Iseconds) OK ${host}:${port}"
    else
      echo "$(date -Iseconds) DOWN ${host}:${port}"
    fi
  done
  sleep 60
done
