#!/bin/sh
# NEO-P2P health checker — pings all services every 60s.
# Mounted into the healthchecker container (see docker-compose*.yml).
#
# Runs with network_mode: host — the container shares the host network
# namespace, so it can reach the backends via 127.0.0.1 + host-mapped port.
#
# HAProxy checks use loopback TLS binds (17001-17004, 14003), NOT the public
# hostname: Oracle Cloud does not support hairpin NAT, so an instance cannot
# reach its own public IP — a public-hostname check from the healthchecker
# always fails even when HAProxy is healthy (external hosts reach it fine).
# The loopback binds exercise the same TLS termination + backend routing.
set -e

apk add --no-cache curl >/dev/null 2>&1 || true

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

  # ── HAProxy checks (loopback TLS binds) ──
  for ep in 17001 17002 17003 17004; do
    if curl -skf --max-time 10 "https://127.0.0.1:${ep}/" >/dev/null 2>&1; then
      echo "$(date -Iseconds) OK haproxy:${ep}"
    else
      echo "$(date -Iseconds) DOWN haproxy:${ep}"
    fi
  done
  if curl -skf --max-time 10 "https://127.0.0.1:14003/health" >/dev/null 2>&1; then
    echo "$(date -Iseconds) OK haproxy:14003 (ws-relay)"
  else
    echo "$(date -Iseconds) DOWN haproxy:14003 (ws-relay)"
  fi

  sleep 60
done
