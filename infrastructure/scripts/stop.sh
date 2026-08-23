#!/usr/bin/env bash
# Stop all NEO-P2P relay services (containers + network; named volumes persist)
set -euo pipefail

# Resolve the infrastructure directory relative to this script's own location,
# so the scripts work from any cwd and on any server layout:
#   repo layout:            <infra>/scripts/stop.sh  → <infra>
#   flat copy:              <dir>/stop.sh            → <dir> (compose alongside)
#   scripts/ + infra/ sibs: <dir>/scripts/stop.sh   → <dir>/infrastructure
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

# Select the compose file matching the host architecture (same logic as deploy.sh)
case "$(uname -m)" in
  aarch64|arm64) COMPOSE_FILE="docker-compose.yml" ;;
  x86_64|amd64)  COMPOSE_FILE="docker-compose.amd64.yml" ;;
  *) echo "Unsupported architecture: $(uname -m)" >&2; exit 1 ;;
esac

echo "Stopping all NEO-P2P services ($COMPOSE_FILE)..."
docker compose -f "$COMPOSE_FILE" down

echo ""
echo "All services stopped. Relay data (named volumes) is preserved."
