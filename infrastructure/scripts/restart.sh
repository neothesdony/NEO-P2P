#!/usr/bin/env bash
# Restart all NEO-P2P relay services
set -euo pipefail

cd "$(dirname "$0")/.."

echo "Restarting all NEO-P2P services..."
docker compose restart

echo ""
echo "Waiting 5s for services to come up..."
sleep 5

bash scripts/status.sh
