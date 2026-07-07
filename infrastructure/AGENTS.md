# Server Infrastructure — infrastructure/

## Purpose

Server-side deployment infrastructure for NEO-P2P relay network. Runs on Oracle Cloud Free Tier (ARM64, 4 cores, 24GB RAM). Provides Nostr relays (Strfry), libp2p circuit relay for NAT traversal, and Coturn TURN/STUN server for WebRTC connectivity.

## Ownership

- **Owner:** DevOps
- **Scope:** `infrastructure/` — `docker-compose.yml`, Dockerfile for libp2p relay, Strfry configs, Coturn config, deployment/management scripts

## Local Contracts

- **Docker Compose stack:**
  - 3× Strfry Nostr relays (ports 7001-7003, 0.5 CPU / 128MB RAM each)
  - 1× Strfry metadata relay (port 7004, NIP-65, 0.25 CPU / 64MB RAM)
  - 1× libp2p circuit relay (port 4001, Go binary, Docker build)
  - 1× Coturn TURN/STUN (ports 3478 TCP+UDP, 5349 TLS, 50000-50010 UDP relay)
  - 1× Alpine-based health checker container
- **Scrips** (`scripts/`):
  - `deploy.sh` — Deploy full stack
  - `restart.sh` — Restart services
  - `status.sh` — Check service health
  - `backup.sh` — Backup relay data
- **Networking:** Docker bridge network `neo-p2p` (172.20.0.0/24)
- **Config files:** Strfry JSON configs in `strfry/`, Coturn config in `coturn/turnserver.conf`

## Work Guidance

- All services target `linux/arm64` platform (Oracle Free Tier ARM)
- Strfry relays are stateless (data in named volumes) for easy restore
- libp2p relay built from Go source in `libp2p-relay/` subdirectory
- Coturn uses `diamondburned/coturn:latest` image
- TURN credentials must be updated in both Docker config and Android `BuildConfig`
- No secrets committed to repo — use `.env` file for sensitive values

## Verification

- `docker compose ps` from `infrastructure/` to verify all services running
- `docker compose logs <service>` for per-service diagnostics
- Health checker container logs show OK/DOWN per endpoint every 60s

## Child DOX Index

| Subpath | Owner | Purpose |
|---------|-------|---------|
| `strfry/` | DevOps | Nostr relay JSON configuration files (3 data relays + 1 metadata relay) |
| `libp2p-relay/` | DevOps | Go source + Dockerfile for libp2p circuit relay v2 |
| `coturn/` | DevOps | Coturn TURN/STUN server configuration |
| `scripts/` | DevOps | Deploy, restart, status, and backup shell scripts |
