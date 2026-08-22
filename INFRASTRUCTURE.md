# Infrastructure

## NEO-P2P Relay Deployment Guide

This directory contains everything needed to deploy NEO-P2P's relay infrastructure on **Oracle Cloud Free Tier** (4 ARM cores, 24GB RAM — permanently free) or any x86_64 VPS.

## Architecture

```
┌──────────────────────────────────────────────────┐
│            Oracle Cloud Free Tier VM (ARM64)      │
│               or any x86_64 VPS (amd64)           │
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

## Compose Files

- `docker-compose.yml` — **ARM64** (Oracle Cloud Free Tier)
- `docker-compose.amd64.yml` — **AMD64/x86_64** (any x86_64 VPS)

`deploy.sh` auto-detects the host architecture (`uname -m`) and selects the matching file.

## One-Command Deploy

```bash
# From a machine with Docker and SSH access to the relay VM:
bash infrastructure/scripts/deploy.sh relay.example.com
```

This will:
1. Install Docker + Docker Compose on the VM
2. Auto-detect architecture and use the matching compose file
3. Set `RELAY_DOMAIN` (domain arg or public IP) in `.env`
4. Start all containers (strfry entrypoint substitutes `RELAY_DOMAIN` into configs)
5. Verify health

## Manual Setup

### 1. Create a VM
- **ARM64 (Oracle Free Tier)**: VM.Standard.A1.Flex (4 OCPU, 24GB RAM)
- **AMD64**: any x86_64 VPS (Hetzner, DigitalOcean, AWS EC2, etc.)
- **OS**: Ubuntu 22.04 LTS / 24.04 LTS
- **Storage**: 200GB boot volume
- **Network**: Enable ports 7001-7004 (Nostr), 4001-4002 (libp2p), 4003 (WS relay), 3478/5349 (TURN)

### 2. SSH and Deploy

```bash
ssh ubuntu@your-vm-ip
git clone <repo-url>
cd neo-p2p/infrastructure
export RELAY_DOMAIN=relay.example.com   # optional; defaults to custom-minipc.com
# ARM64:
docker compose -f docker-compose.yml up -d
# OR AMD64:
docker compose -f docker-compose.amd64.yml up -d
```

### 3. Verify

```bash
# Check containers
docker ps

# Check relay connectivity (from local machine)
curl -H "Accept: application/nostr+json" https://relay.example.com:7001
```

## Components

### strfry (Nostr Relay)
- **Image**: `ghcr.io/hoytech/strfry:latest` (official GHCR). Do NOT use `herrrring/strfry` — it does not exist on Docker Hub.
- **Config**: `strfry/strfry-{1,2,3,meta}.conf` (INI format, mounted to `/app/strfry.conf`)
- **Ports**: 7001-7004
- **Data**: persistent, named volumes mounted to `/app/strfry-db`
- **Domain**: each config uses `__RELAY_DOMAIN__` in `relay.auth.serviceUrl`; the entrypoint substitutes it at container start
- **Performance**: Each relay handles ~10K concurrent connections on Oracle Free Tier

### libp2p Circuit Relay v2
- **Source**: `libp2p-relay/main.go`
- **Port**: 4001
- **Features**: Circuit relay v2, Prometheus metrics at `/metrics`
- **Resource limits**: 200 concurrent reservations, 100MB relayed data per connection

### ws-relay (P2P WebSocket fallback)
- **Source**: `ws-relay/main.go`
- **Port**: 4003
- **Purpose**: WebSocket transport fallback for strict NAT/firewall

### coturn (TURN/STUN)
- **Config**: `coturn/turnserver.conf`
- **Ports**: 3478 (TURN+STUN), 5349 (TLS), 50000-50010 (UDP relay)
- **Auth**: `neop2p:changeme` (CHANGE THIS)
- **Realm**: `custom-minipc.com`
- **Usage**: Only for worst-case Indonesian CGNAT (~20% of users)

## Management Scripts

| Script | Purpose |
|--------|---------|
| `deploy.sh domain.com` | Full VM setup + deploy (auto-selects compose file)
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
2. **Set up UFW firewall** on the VM
3. **Use Cloudflare** for Nostr relay DNS (DDoS protection)
4. **Monitor logs**: `docker compose -f <compose-file> logs -f`
5. **Regular backups**: `bash scripts/backup.sh`

## Adding a Custom Relay

1. Add a service to the compose file
2. Create a `strfry-*.conf` in `strfry/`
3. Expose the port in the cloud firewall
4. Update `NeoP2PConfig.kt` DEFAULT_NOSTR_RELAYS
5. Rebuild and release a new APK

## Troubleshooting

**Relays not connecting?**
```bash
# Check if strfry is running
docker logs neop2p-nostr-1

# Check port accessibility
nc -zv localhost 7001

# Check firewall
sudo ufw status
```

**Domain not applied?**
```bash
# Confirm RELAY_DOMAIN is set
docker exec neop2p-nostr-1 cat /tmp/strfry.conf | grep serviceUrl
```

**libp2p relay not working?**
```bash
# Check metrics
curl http://localhost:4001/metrics

# Check connections
docker logs neop2p-libp2p-relay
```

**TURN not responding?**
```bash
# Test STUN
turnutils_stunclient -p 3478 relay.example.com

# Check coturn logs
docker logs neop2p-turn
```
