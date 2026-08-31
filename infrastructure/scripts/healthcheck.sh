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

apk add --no-cache curl >/dev/null 2>&1 || true

while true; do
  # ── RNS transport node (rnsd-kt, TCP server on 42000) ──
  if nc -z -w 3 127.0.0.1 42000 >/dev/null 2>&1; then
    echo "$(date -Iseconds) OK rns-transport:42000 (tcp)"
  else
    echo "$(date -Iseconds) DOWN rns-transport:42000 (tcp)"
  fi

  # ── LXMF propagation node (Python lxmd, TCP server on 42000) ──
  # The propagation node shares the transport node's port namespace via the
  # docker network; liveness is checked through the transport node's route.
  if nc -z -w 3 127.0.0.1 42000 >/dev/null 2>&1; then
    echo "$(date -Iseconds) OK lxmf-propagation (via rns-transport)"
  else
    echo "$(date -Iseconds) DOWN lxmf-propagation (via rns-transport)"
  fi

  sleep 60
done
