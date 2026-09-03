# Infrastructure

## NEO-P2P RNS Deployment Guide

This directory contains everything needed to deploy NEO-P2P's infrastructure on **Oracle Cloud Free Tier** (4 ARM cores, 24GB RAM — permanently free) or any x86_64 VPS.

**Phase 4 (2026-08-31):** the Nostr relays (strfry ×4), libp2p circuit relay, WS relay, and coturn were **removed** — replaced by the RNS transport node + LXMF propagation node. **2026-09-01:** the transport node now runs the **official Python rnsd** (replaced the rnsd-kt fork).

## Architecture

```
┌──────────────────────────────────────────────────┐
│            Oracle Cloud Free Tier VM (ARM64)      │
│               or any x86_64 VPS (amd64)           │
│                                                    │
│  ┌────────────────────┐  ┌──────────────────────┐  │
│  │   rns-transport    │  │   lxmf-propagation   │  │
│  │  (Python rnsd)     │  │   (Python lxmd)      │  │
│  │  TCP server :42000 │  │  store-and-forward   │  │
│  │  enableTransport   │  │  (offline peers)     │  │
│  └─────────┬──────────┘  └──────────┬───────────┘  │
│            │  [[Propagation Link]] │              │
│            └───────────────────────┘              │
│                                                    │
│          Docker Compose (all containers)            │
└──────────────────────────────────────────────────┘
```

Phones connect as TCP clients to `rns-transport:42000`; the node routes announces, paths, and links between peers and to the propagation node. The propagation node provides store-and-forward for offline peers.

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
3. Start both containers (rns-transport + lxmf-propagation)
4. Verify health

## Manual Setup

### 1. Create a VM
- **ARM64 (Oracle Free Tier)**: VM.Standard.A1.Flex (4 OCPU, 24GB RAM)
- **AMD64**: any x86_64 VPS (Hetzner, DigitalOcean, AWS EC2, etc.)
- **OS**: Ubuntu 22.04 LTS / 24.04 LTS
- **Storage**: 200GB boot volume
- **Network**: Enable port 42000 (RNS transport TCP)

### 2. SSH and Deploy

```bash
ssh ubuntu@your-vm-ip
git clone <repo-url>
cd neo-p2p/infrastructure
# ARM64:
docker compose -f docker-compose.yml up -d --build
# OR AMD64:
docker compose -f docker-compose.amd64.yml up -d --build
```

### 3. Verify

```bash
# Check containers
docker ps

# Check the transport node is listening (from the VM)
nc -z 127.0.0.1 42000

# Check logs
docker compose -f docker-compose.yml logs rns-transport --tail 30
docker compose -f docker-compose.yml logs lxmf-propagation --tail 30
```

## Components

### rns-transport (RNS Transport Node)
- **Image**: `python:3.11-slim` + `pip install rns lxmf` (lxmf is a HARD runtime dep — the TCP server interface auto-configures to gateway mode and hard-panics without it)
- **Config**: `rns-transport/config` (rnsd loads exactly `File(dir, "config")` — no extension; bind-mounted `:ro` into the container)
- **Port**: 42000 (raw Reticulum TCP interface, not HTTP)
- **Data**: `rns-transport-data` named volume at `/etc/reticulum` (identity + destination cache only)
- **Healthcheck**: python socket probe (slim image has no bash)
- **Announce rate limiter (REQUIRED):** every interface section MUST set `announce_rate_target = 1`, `announce_rate_grace = 20`, `announce_rate_penalty = 0` — the Python rnsd default (`announce_rate_target = 3600`) blocks the app's destinations for an hour. See `infrastructure/AGENTS.md`.

### lxmf-propagation (LXMF Propagation Node)
- **Image**: `python:3.11-slim` + `pip install lxmf`
- **Entrypoint**: `lxmd --config <dir> --rnsconfig <dir> -p` (propagation node)
- **Data**: `lxmf-propagation-data` named volume at `/var/lib/lxmf` (identity must be STABLE across restarts — peers cache the node's destination hash)
- **Caps**: PROPAGATION_LIMIT=256 messages, DELIVERY_LIMIT=1000, MESSAGE_EXPIRY=30 days; daily prune (guarded — slim image has no cron)

## Management Scripts

| Script | Purpose |
|--------|---------|
| `deploy.sh domain.com` | Full VM setup + deploy (auto-selects compose file) |
| `status.sh` | Health check + container status |
| `restart.sh` | Graceful restart of all services |
| `backup.sh` | Snapshot RNS config + propagation store |
| `stop.sh` | Stop all services |

## Resource Usage

| Container | RAM | CPU | Storage |
|-----------|-----|-----|---------|
| rns-transport | ~100MB | ~0.5% idle | ~50MB |
| lxmf-propagation | ~100MB | ~0.5% idle | ~50MB |
| **Total** | **~200MB** | **~1%** | **~100MB** |

Well within Oracle Free Tier limits.

## Security Notes

1. **The transport node is a packet ferry, not a trust anchor** — traffic stays end-to-end encrypted and announces are signed, so more nodes = more reach, never less security.
2. **Set up UFW firewall** on the VM (only 42000/tcp inbound).
3. **Monitor logs**: `docker compose -f <compose-file> logs -f`
4. **Regular backups**: `bash scripts/backup.sh`
5. **No secrets committed to repo** — use `.env` file for sensitive values.

## Adding a Transport Node

Users can add extra RNS transport nodes in the app (Settings → transport nodes). To host one:
1. Deploy this stack (or just the `rns-transport` service) on another VPS
2. Open port 42000 in the cloud firewall
3. Share `host:port` with users — they add it in Settings (live-apply, no restart)

## Troubleshooting

**Phones not connecting?**
```bash
# Check if rns-transport is running
docker logs neop2p-rns-transport

# Check port accessibility
nc -zv localhost 42000

# Check firewall
sudo ufw status
```

**Transport node blocking announces?**
```bash
# Look for "Blocking rebroadcast ... due to excessive announce rate"
docker logs neop2p-rns-transport | grep -i "announce rate"
# Fix: ensure announce_rate_target=1, announce_rate_grace=20, announce_rate_penalty=0
# in every interface section of rns-transport/config
```

**Propagation node crash-looping?**
```bash
docker logs neop2p-lxmf-propagation
# The lxmd invocation must be: lxmd --config <dir> --rnsconfig <dir> -p
# (--identity is NOT a CLI option; -p enables propagation-node mode)
```
