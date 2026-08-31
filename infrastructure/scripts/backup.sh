#!/usr/bin/env bash
# Backup relay data + configs
set -euo pipefail

# Resolve the infrastructure directory relative to this script's own location,
# so the scripts work from any cwd and on any server layout:
#   repo layout:            <infra>/scripts/backup.sh  → <infra>
#   flat copy:              <dir>/backup.sh            → <dir> (compose alongside)
#   scripts/ + infra/ sibs: <dir>/scripts/backup.sh   → <dir>/infrastructure
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

# Backup destination — override with NEO_P2P_BACKUP_DIR on any server
BACKUP_DIR="${NEO_P2P_BACKUP_DIR:-$INFRA_DIR/backups}"
TIMESTAMP=$(date +%Y%m%d_%H%M%S)
BACKUP_PATH="${BACKUP_DIR}/${TIMESTAMP}"

mkdir -p "$BACKUP_PATH"

echo "Backing up NEO-P2P RNS data to ${BACKUP_PATH}..."

# Configs
cp -r rns-transport/*.yml "$BACKUP_PATH/" 2>/dev/null || true
cp -r lxmf-propagation/*.sh "$BACKUP_PATH/" 2>/dev/null || true
cp docker-compose.yml "$BACKUP_PATH/"
cp docker-compose.amd64.yml "$BACKUP_PATH/" 2>/dev/null || true

# Docker volumes (RNS transport config + identity, LXMF propagation store)
docker run --rm -v neop2p_rns-transport-data:/data -v "$BACKUP_PATH:/backup" alpine cp -r /data /backup/rns-transport 2>/dev/null || true
docker run --rm -v neop2p_lxmf-propagation-data:/data -v "$BACKUP_PATH:/backup" alpine cp -r /data /backup/lxmf-propagation 2>/dev/null || true

echo "Backup complete: ${BACKUP_PATH}"
ls -la "$BACKUP_PATH/"

# Keep last 7 days, delete older
find "$BACKUP_DIR" -maxdepth 1 -type d -mtime +7 -exec rm -rf {} \; 2>/dev/null || true
