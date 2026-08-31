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

echo "=== NEO-P2P RNS Status ==="
echo ""

# Check each service
for svc in neop2p-rns-transport neop2p-lxmf-propagation neop2p-health; do
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
ss -tlnp | grep -E '42000' || echo "(check firewall)"

echo ""
echo "=== Health Check ==="
nc -z -w 3 127.0.0.1 42000 && echo "rns-transport:42000 OK" || echo "rns-transport:42000 DOWN"
