# Infrastructure

## NEO-P2P Relay Deployment Guide

This directory contains everything needed to deploy NEO-P2P's relay infrastructure on **Oracle Cloud Free Tier** (4 ARM cores, 24GB RAM — permanently free).

## Architecture

```
┌──────────────────────────────────────────────────┐
│              Oracle Cloud Free Tier VM            │
│                                                    │
│  ┌──────┐ ┌──────┐ ┌──────┐ ┌────────┐ ┌──────┐  │
│  │strfry│ │strfry│ │strfry│ │ strfry │ │ libp2p│ │
│  │ #1   │ │ #2   │ │ #3   │ │ meta   │ │relay │  │
│  │:7001 │ │:7002 │ │:7003 │ │:7004   │ │:4001 │  │
│  └──────┘ └──────┘ └──────┘ └────────┘ └──────┘  │
│                                                    │
│          Docker Compose (all containers)            │
└──────────────────────────────────────────────────┘
```

## One-Command Deploy

```bash
# From a machine with Docker and SSH access to Oracle VM:
bash infrastructure/scripts/deploy.sh relay1.neop2p.io
```

This will:
1. Install Docker + Docker Compose on the VM
2. Copy all config files
3. Start all containers
4. Verify health

## Manual Setup

### 1. Create Oracle Cloud VM
- **Shape**: VM.Standard.A1.Flex (4 OCPU, 24GB RAM)
- **OS**: Ubuntu 22.04 LTS
- **Storage**: 200GB boot volume
- **Network**: Enable ports 7001-7004 (Nostr), 4001 (libp2p), 3478 (TURN)

### 2. SSH and Deploy

```bash
ssh ubuntu@your-vm-ip
git clone https://code.neop2p.io/thesdony/neo-p2p.git
cd neo-p2p/infrastructure
docker compose up -d
```

### 3. Verify

```bash
# Check containers
docker ps

# Check relay connectivity (from local machine)
curl -H "Accept: application/nostr+json" https://relay1.neop2p.io:7001
```

## Components

### strfry (Nostr Relay)
- **Config**: `strfry/config-*.json`
- **Ports**: 7001-7004
- **Data**: persistent, mounted to `./data/strfry-*/`
- **Performance**: Each relay handles ~10K concurrent connections on Oracle Free Tier

### libp2p Circuit Relay v2
- **Source**: `libp2p-relay/main.go`
- **Port**: 4001
- **Features**: Circuit relay v2, Prometheus metrics at `/metrics`
- **Resource limits**: 200 concurrent reservations, 100MB relayed data per connection

### coturn (TURN/STUN)
- **Config**: `coturn/turnserver.conf`
- **Ports**: 3478 (TURN+STUN)
- **Auth**: `neop2p:changeme` (CHANGE THIS)
- **Usage**: Only for worst-case Indonesian CGNAT (~20% of users)

## Management Scripts

| Script | Purpose |
|--------|---------|
| `deploy.sh domain.com` | Full VM setup + deploy |
| `status.sh` | Health check + container status |
| `restart.sh` | Graceful restart of all relays |
| `backup.sh` | Snapshot databases + configs |

## Resource Usage

| Container | RAM | CPU | Storage |
|-----------|-----|-----|---------|
| strfry (×4) | ~800MB | ~1% idle | ~5GB |
| libp2p relay | ~400MB | ~0.5% idle | ~100MB |
| coturn | ~100MB | ~0% idle | ~50MB |
| **Total** | **~1.3GB** | **~2%** | **~6GB** |

Well within Oracle Free Tier limits.

## Security Notes

1. **Change TURN credentials** in `coturn/turnserver.conf`
2. **Set up UFW firewall** on Oracle VM
3. **Use Cloudflare** for Nostr relay DNS (DDoS protection)
4. **Monitor logs**: `docker compose logs -f`
5. **Regular backups**: `bash scripts/backup.sh`

## Adding a Custom Relay

1. Add to `docker-compose.yml`
2. Create config in `strfry/`
3. Expose port in Oracle Cloud firewall
4. Update `NeoP2PConfig.kt` DEFAULT_NOSTR_RELAYS
5. Rebuild and release new APK

## Troubleshooting

**Relays not connecting?**
```bash
# Check if strfry is running
docker logs infrastructure-strfry-1

# Check port accessibility
nc -zv localhost 7001

# Check firewall
sudo ufw status
```

**libp2p relay not working?**
```bash
# Check metrics
curl http://localhost:4001/metrics

# Check connections
docker logs infrastructure-libp2p-relay-1
```

**TURN not responding?**
```bash
# Test STUN
turnutils_stunclient -p 3478 relay1.neop2p.io

# Check coturn logs
docker logs infrastructure-coturn-1
```
