#!/bin/sh
# NEO-P2P health checker — pings all services every 60s.
# Mounted into the healthchecker container (see docker-compose*.yml).
#
# Runs with network_mode: host — the container shares the host network
# namespace, so it can reach:
#   - the HAProxy TLS frontends via the public hostname (catches HAProxy death)
#   - every backend via 127.0.0.1 + host-mapped port (no bridge DNS needed)
# A bridge-network container could NOT reach the public IP (ufw blocks the
# hairpin), which made every public check DOWN even when healthy.
#
# Public base is derived from RELAY_DOMAIN (set via the compose anchor) and
# overridable with NEO_P2P_PUBLIC_BASE for non-standard setups.
set -e

apk add --no-cache curl >/dev/null 2>&1 || true

PUBLIC_BASE="${NEO_P2P_PUBLIC_BASE:-https://relay1.${RELAY_DOMAIN:-custom-minipc.com}}"

while true; do
  # ── Internal checks (host-mapped ports, no TLS) ──
  for port in 7001 7002 7003 7004; do
    if curl -sf --max-time 5 "http://127.0.0.1:${port}" >/dev/null 2>&1; then
      echo "$(date -Iseconds) OK strfry:${port}"
    else
      echo "$(date -Iseconds) DOWN strfry:${port}"
    fi
  done
  if curl -sf --max-time 5 "http://127.0.0.1:4002/health" >/dev/null 2>&1; then
    echo "$(date -Iseconds) OK libp2p-relay:4002"
  else
    echo "$(date -Iseconds) DOWN libp2p-relay:4002"
  fi
  if curl -sf --max-time 5 "http://127.0.0.1:4003/health" >/dev/null 2>&1; then
    echo "$(date -Iseconds) OK ws-relay:4003"
  else
    echo "$(date -Iseconds) DOWN ws-relay:4003"
  fi

  # coturn speaks TURN/STUN, not HTTP — curl can never succeed against it.
  # TCP connect is the honest liveness check (a real STUN binding would need
  # a UDP client; nc -z covers the "process up + port bound" case).
  if nc -z -w 3 127.0.0.1 3478 >/dev/null 2>&1; then
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
