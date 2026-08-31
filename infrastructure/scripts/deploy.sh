#!/usr/bin/env bash
# NEO-P2P Relay Infrastructure — One-Command Deploy
# Usage: bash deploy.sh [domain.com]
#
# Prerequisites:
#   - Oracle Cloud Free Tier VM (Ubuntu 24.04, ARM64) OR any x86_64 VPS
#   - Docker + Docker Compose installed
#   - DNS records pointing to this VM (optional)
#
# Architecture is auto-detected:
#   - arm64  → uses docker-compose.yml
#   - amd64  → uses docker-compose.amd64.yml

set -euo pipefail

# Resolve the infrastructure directory relative to this script's own location,
# so the scripts work from any cwd and on any server layout:
#   repo layout:            <infra>/scripts/deploy.sh  → <infra>
#   flat copy:              <dir>/deploy.sh            → <dir> (compose alongside)
#   scripts/ + infra/ sibs: <dir>/scripts/deploy.sh   → <dir>/infrastructure
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

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

DOMAIN="${1:-}"
PUBLIC_IP=$(curl -sf https://api.ipify.org || curl -sf https://ifconfig.me)

# Detect host architecture and select the matching compose file
ARCH=$(uname -m)
case "$ARCH" in
  aarch64|arm64)
    COMPOSE_FILE="docker-compose.yml"
    ARCH_LABEL="ARM64"
    ;;
  x86_64|amd64)
    COMPOSE_FILE="docker-compose.amd64.yml"
    ARCH_LABEL="AMD64"
    ;;
  *)
    echo -e "${RED}Unsupported architecture: $ARCH${NC}"
    exit 1
    ;;
esac

echo -e "${BLUE}══════════════════════════════════════════${NC}"
echo -e "${BLUE}  NEO-P2P Relay Infrastructure Deploy     ${NC}"
echo -e "${BLUE}  Public IP: ${PUBLIC_IP}                  ${NC}"
echo -e "${BLUE}  Arch: ${ARCH_LABEL} (${COMPOSE_FILE})        ${NC}"
[[ -n "$DOMAIN" ]] && echo -e "${BLUE}  Domain: ${DOMAIN}                        ${NC}"
echo -e "${BLUE}══════════════════════════════════════════${NC}"
echo ""

# ── Prerequisites ──
echo -e "${YELLOW}[1/6] Checking prerequisites...${NC}"

if ! command -v docker &>/dev/null; then
    echo -e "${YELLOW}Installing Docker...${NC}"
    curl -fsSL https://get.docker.com | bash
    sudo usermod -aG docker "$USER"
    echo -e "${GREEN}Docker installed${NC}"
else
    echo -e "${GREEN}Docker: $(docker --version)${NC}"
fi

if ! docker compose version &>/dev/null; then
    echo -e "${YELLOW}Installing Docker Compose plugin...${NC}"
    sudo apt-get update -qq
    sudo apt-get install -y -qq docker-compose-plugin
fi
echo -e "${GREEN}Docker Compose: $(docker compose version)${NC}"

# ── Firewall ──
echo -e "${YELLOW}[2/6] Configuring firewall...${NC}"
if command -v ufw &>/dev/null; then
    sudo ufw --force enable 2>/dev/null || true
    for port in 42000; do
        sudo ufw allow "$port/tcp" 2>/dev/null || true
    done
    echo -e "${GREEN}Firewall ports opened${NC}"
else
    echo -e "${YELLOW}ufw not found — ensure ports are open in Oracle firewall${NC}"
    echo -e "${YELLOW}Required: 42000/tcp (RNS transport node)${NC}"
fi

# ── Configure Coturn Public IP ──
echo -e "${YELLOW}[3/6] Configuring Coturn public IP...${NC}"
# Phase 4: coturn was removed (RNS TCP client mode needs no TURN). Kept as a
# no-op step so the deploy flow stays numbered.
echo -e "${GREEN}Coturn: removed in Phase 4 (RNS needs no TURN)${NC}"

# ─── Configure relay domain ──
echo -e "${YELLOW}[4/6] Configuring relay domain...${NC}"
# Phase 4: no strfry relays — the RNS transport node + LXMF propagation node
# need no domain substitution. Kept as a no-op step for flow continuity.
RELAY_DOMAIN="${DOMAIN:-${PUBLIC_IP}}"
export RELAY_DOMAIN
echo -e "${GREEN}Relay domain set to: ${RELAY_DOMAIN}${NC}"

# ── Generate .env ──
echo -e "${YELLOW}[5/6] Creating .env...${NC}"
cat > .env <<EOF
# NEO-P2P RNS Environment
NEO_P2P_PUBLIC_IP=${PUBLIC_IP}
NEO_P2P_DOMAIN=${DOMAIN:-}
RELAY_DOMAIN=${RELAY_DOMAIN}
NEO_P2P_DEPLOYED_AT=$(date -Iseconds)
EOF
echo -e "${GREEN}.env created${NC}"

# ── Pull & Start ──
echo -e "${YELLOW}[6/6] Deploying containers...${NC}"
docker compose -f "$COMPOSE_FILE" pull
docker compose -f "$COMPOSE_FILE" up -d

echo ""
echo -e "${GREEN}══════════════════════════════════════════${NC}"
echo -e "${GREEN}  NEO-P2P RNS Infrastructure ACTIVE!       ${NC}"
echo -e "${GREEN}══════════════════════════════════════════${NC}"
echo ""
echo -e "  RNS Transport Node:"
echo -e "    tcp://${RELAY_DOMAIN}:42000 (rnsd-kt, enableTransport=true)"
echo ""
echo -e "  LXMF Propagation Node:"
echo -e "    store-and-forward for offline peers (Python lxmd)"
echo ""
echo -e "  Monitor:"
echo -e "    docker compose -f ${COMPOSE_FILE} ps"
echo -e "    docker compose -f ${COMPOSE_FILE} logs -f"
echo ""

# ── Show status ──
docker compose -f "$COMPOSE_FILE" ps
