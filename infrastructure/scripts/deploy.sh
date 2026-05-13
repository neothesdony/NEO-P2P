#!/usr/bin/env bash
# NEO-P2P Relay Infrastructure — One-Command Deploy
# Usage: bash deploy.sh [domain.com]
#
# Prerequisites:
#   - Oracle Cloud Free Tier VM (Ubuntu 24.04, ARM64)
#   - Docker + Docker Compose installed
#   - DNS records pointing to this VM (optional)

set -euo pipefail

RED='\033[0;31m'
GREEN='\033[0;32m'
YELLOW='\033[1;33m'
BLUE='\033[0;34m'
NC='\033[0m'

DOMAIN="${1:-}"
PUBLIC_IP=$(curl -sf https://api.ipify.org || curl -sf https://ifconfig.me)

echo -e "${BLUE}══════════════════════════════════════════${NC}"
echo -e "${BLUE}  NEO-P2P Relay Infrastructure Deploy     ${NC}"
echo -e "${BLUE}  Public IP: ${PUBLIC_IP}                  ${NC}"
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
    for port in 7001 7002 7003 7004 4001 4002 3478 5349; do
        sudo ufw allow "$port/tcp" 2>/dev/null || true
    done
    sudo ufw allow 3478/udp 2>/dev/null || true
    sudo ufw allow 50000:50010/udp 2>/dev/null || true
    echo -e "${GREEN}Firewall ports opened${NC}"
else
    echo -e "${YELLOW}ufw not found — ensure ports are open in Oracle firewall${NC}"
    echo -e "${YELLOW}Required: 7001-7004/tcp, 4001-4002/tcp, 3478/tcp+udp, 5349/tcp, 50000-50010/udp${NC}"
fi

# ── Configure Coturn Public IP ──
echo -e "${YELLOW}[3/6] Configuring Coturn public IP...${NC}"
if [[ -f coturn/turnserver.conf ]]; then
    sed -i "s/external-ip=YOUR_PUBLIC_IP/external-ip=${PUBLIC_IP}/" coturn/turnserver.conf
    echo -e "${GREEN}Coturn configured with public IP: ${PUBLIC_IP}${NC}"
fi

# ─── Configure strfry relay URLs ──
echo -e "${YELLOW}[4/6] Configuring relay URLs...${NC}"
RELAY_BASE="${DOMAIN:-${PUBLIC_IP}}"
for i in 1 2 3; do
    if [[ -f "strfry/config-${i}.json" ]]; then
        sed -i "s|wss://relay${i}.neop2p.io:700${i}|wss://${RELAY_BASE}:700${i}|" "strfry/config-${i}.json"
    fi
done
if [[ -f strfry/config-meta.json ]]; then
    sed -i "s|wss://meta.neop2p.io:7004|wss://${RELAY_BASE}:7004|" strfry/config-meta.json
fi
echo -e "${GREEN}Relay URLs configured to: ${RELAY_BASE}${NC}"

# ── Generate .env ──
echo -e "${YELLOW}[5/6] Creating .env...${NC}"
cat > .env <<EOF
# NEO-P2P Relay Environment
NEO_P2P_PUBLIC_IP=${PUBLIC_IP}
NEO_P2P_DOMAIN=${DOMAIN:-}
NEO_P2P_COTURN_SECRET=$(openssl rand -hex 16)
NEO_P2P_DEPLOYED_AT=$(date -Iseconds)
EOF
echo -e "${GREEN}.env created${NC}"

# ── Pull & Start ──
echo -e "${YELLOW}[6/6] Deploying containers...${NC}"
docker compose pull
docker compose up -d

echo ""
echo -e "${GREEN}══════════════════════════════════════════${NC}"
echo -e "${GREEN}  NEO-P2P Relay Infrastructure ACTIVE!    ${NC}"
echo -e "${GREEN}══════════════════════════════════════════${NC}"
echo ""
echo -e "  Nostr Relays:"
echo -e "    wss://${RELAY_BASE}:7001"
echo -e "    wss://${RELAY_BASE}:7002"
echo -e "    wss://${RELAY_BASE}:7003"
echo -e "    wss://${RELAY_BASE}:7004 (NIP-65 metadata)"
echo ""
echo -e "  libp2p Circuit Relay:"
echo -e "    /dns/${RELAY_BASE}/tcp/4001/p2p-circuit"
echo ""
echo -e "  TURN/STUN:"
echo -e "    turn:${RELAY_BASE}:3478 (user: neop2p)"
echo -e "    stun:${RELAY_BASE}:3478 (free, no auth)"
echo ""
echo -e "  Monitor:"
echo -e "    docker compose ps"
echo -e "    docker compose logs -f"
echo ""

# ── Show status ──
docker compose ps
