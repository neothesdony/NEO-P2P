#!/usr/bin/env bash
set -euo pipefail

# Resolve the infrastructure directory relative to this script's own location,
# so the scripts work from any cwd and on any server layout:
#   repo layout:            <infra>/scripts/status.sh  → <infra>
#   flat copy:              <dir>/status.sh            → <dir> (compose alongside)
#   scripts/ + infra/ sibs: <dir>/scripts/status.sh   → <dir>/infrastructure
SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
for CANDIDATE in "$SCRIPT_DIR" "$SCRIPT_DIR/.." "$SCRIPT_DIR/../infrastructure"; do
  if [[ -f "$CANDIDATE/docker-compose.yml" ]]; then
    INFRA_DIR="$(cd "$CANDIDATE" && pwd)"
    break
  fi
done
if [[ -z "${INFRA_DIR:-}" ]]; then
  echo "ERROR: docker-compose.yml not found near $SCRIPT_DIR (checked script dir, parent, parent/infrastructure)" >&2
  exit 1
fi
cd "$INFRA_DIR"

echo "=== NEO-P2P Relay Status ==="
echo ""

# Check each service
for svc in neop2p-nostr-1 neop2p-nostr-2 neop2p-nostr-3 neop2p-nostr-meta neop2p-libp2p-relay neop2p-turn neop2p-health; do
    if docker ps --format '{{.Names}}' | grep -q "^${svc}$"; then
        STATUS=$(docker inspect "$svc" --format '{{.State.Status}}')
        UPTIME=$(docker inspect "$svc" --format '{{.State.StartedAt}}' | xargs -I{} date -d {} +"%Y-%m-%d %H:%M:%S" 2>/dev/null || echo "unknown")
        echo -e "\e[32m✓\e[0m $svc — $STATUS (since $UPTIME)"
    else
        echo -e "\e[31m✗\e[0m $svc — NOT RUNNING"
    fi
done

echo ""
echo "=== Resource Usage ==="
docker stats --no-stream --format "table {{.Name}}\t{{.CPUPerc}}\t{{.MemUsage}}" 2>/dev/null || echo "docker stats unavailable"

echo ""
echo "=== Port Usage ==="
ss -tlnp | grep -E '700[1-4]|400[1-2]|3478|5349' || echo "(check firewall)"

echo ""
echo "=== Health Check ==="
curl -sf http://localhost:4002/health 2>/dev/null | python3 -m json.tool 2>/dev/null || echo "Health endpoint unreachable"
