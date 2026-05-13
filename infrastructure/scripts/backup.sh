#!/usr/bin/env bash
# Backup relay data + configs
set -euo pipefail

BACKUP_DIR="/backups/neo-p2p"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_PATH="${BACKUP_DIR}/${TIMESTAMP}"

mkdir -p "$BACKUP_PATH"

echo "Backing up NEO-P2P relay data to ${BACKUP_PATH}..."

# Configs
cp -r configs "$BACKUP_PATH/configs" 2>/dev/null || true
cp -r strfry/*.json "$BACKUP_PATH/" 2>/dev/null || true
cp -r coturn/*.conf "$BACKUP_PATH/" 2>/dev/null || true
cp docker-compose.yml "$BACKUP_PATH/"

# Docker volumes (libp2p relay key)
docker run --rm -v neop2p_libp2p-relay-data:/data -v "$BACKUP_PATH:/backup" alpine cp -r /data/relay.key /backup/ 2>/dev/null || true

echo "Backup complete: ${BACKUP_PATH}"
ls -la "$BACKUP_PATH/"

# Keep last 7 days, delete older
find "$BACKUP_DIR" -maxdepth 1 -type d -mtime +7 -exec rm -rf {} \; 2>/dev/null || true
