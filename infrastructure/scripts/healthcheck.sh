#!/bin/sh
# NEO-P2P health checker — pings all services every 60s.
# Mounted into the healthchecker container (see docker-compose*.yml).
#
# Runs with network_mode: host — the container shares the host network
# namespace, so it can reach the backends via 127.0.0.1 + host-mapped port.
#
# Phase 4: the Nostr/libp2p/ws-relay/coturn checks were replaced by the RNS
# transport node + LXMF propagation node checks.
set -e

apk add --no-cache curl netcat-openbsd >/dev/null 2>&1 || true

while true; do
  # ── RNS transport node (Python rnsd, TCP server on host 42420) ──
  if nc -z -w 3 127.0.0.1 42420 >/dev/null 2>&1; then
    echo "$(date -Iseconds) OK rns-transport:42420 (tcp)"
  else
    echo "$(date -Iseconds) DOWN rns-transport:42420 (tcp)"
  fi

  # ── LXMF propagation node (Python lxmd, TCP server on 127.0.0.1:42001) ──
  # Loopback-only host publish (see docker-compose.yml) so this probe is an
  # honest liveness check of the propagation node itself, not a re-check of
  # the transport node. Phones still reach it through the transport node's
  # [[Propagation Link]] (docker DNS).
  if nc -z -w 3 127.0.0.1 42001 >/dev/null 2>&1; then
    echo "$(date -Iseconds) OK lxmf-propagation:42001 (tcp)"
  else
    echo "$(date -Iseconds) DOWN lxmf-propagation:42001 (tcp)"
  fi

  sleep 60
done
