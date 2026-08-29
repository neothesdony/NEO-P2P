#!/bin/sh
# NEO-P2P health checker — pings all services every 60s.
# Mounted into the healthchecker container (see docker-compose*.yml).
#
# Two layers:
#   1. Internal checks on the Docker network (no TLS) — container liveness.
#   2. Public checks through the HAProxy TLS frontends — the surface the app
#      actually talks to. Internal-green + public-red = HAProxy is down.
#
# Public base is derived from RELAY_DOMAIN (set via the compose anchor) and
# overridable with NEO_P2P_PUBLIC_BASE for non-standard setups.
set -e

apk add --no-cache curl jq >/dev/null 2>&1 || true

PUBLIC_BASE="${NEO_P2P_PUBLIC_BASE:-https://relay1.${RELAY_DOMAIN:-custom-minipc.com}}"

while true; do
  # ── Internal checks (Docker network) ──
  for svc in strfry-1:8080 strfry-2:8080 strfry-3:8080 strfry-meta:8080 libp2p-relay:4002 ws-relay:4003; do
    host=${svc%:*}
    port=${svc#*:}
    if curl -sf "http://${host}:${port}" >/dev/null 2>&1; then
      echo "$(date -Iseconds) OK ${host}:${port}"
    else
      echo "$(date -Iseconds) DOWN ${host}:${port}"
    fi
  done

  # coturn speaks TURN/STUN, not HTTP — curl can never succeed against it.
  # TCP connect is the honest liveness check (a real STUN binding would need
  # a UDP client; nc -z covers the "process up + port bound" case).
  if nc -z -w 3 coturn 3478 >/dev/null 2>&1; then
    echo "$(date -Iseconds) OK coturn:3478 (tcp)"
  else
    echo "$(date -Iseconds) DOWN coturn:3478 (tcp)"
  fi

  # ── Public checks through HAProxy (TLS) ──
  for ep in 7001 7002 7003 7004; do
    if curl -skf --max-time 10 "${PUBLIC_BASE}:${ep}/" >/dev/null 2>&1; then
      echo "$(date -Iseconds) OK public:${ep}"
    else
      echo "$(date -Iseconds) DOWN public:${ep}"
    fi
  done
  if curl -skf --max-time 10 "${PUBLIC_BASE}:4003/health" >/dev/null 2>&1; then
    echo "$(date -Iseconds) OK public:4003 (ws-relay)"
  else
    echo "$(date -Iseconds) DOWN public:4003 (ws-relay)"
  fi

  sleep 60
done
