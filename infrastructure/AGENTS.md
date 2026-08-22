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
- **Compose files:**
  - `docker-compose.yml` — **ARM64** (Oracle Cloud Free Tier)
  - `docker-compose.amd64.yml` — **AMD64/x86_64** (any x86_64 VPS)
  - `deploy.sh` auto-detects host arch (`uname -m`) and selects the matching file
- **Scrips** (`scripts/`):
  - `deploy.sh` — Deploy full stack
  - `restart.sh` — Restart services
  - `status.sh` — Check service health
  - `backup.sh` — Backup relay data
- **Networking:** Docker bridge network `neo-p2p` (172.20.0.0/24)
- **Config files:** Strfry JSON configs in `strfry/`, Coturn config in `coturn/turnserver.conf`
- **Relay domain (configurable):**
  - Strfry configs (`strfry-{1,2,3,meta}.conf`, INI format) use a `__RELAY_DOMAIN__` placeholder in `relay.auth.serviceUrl` (NIP-42).
  - `strfry/entrypoint.sh` copies the bind-mounted config to `/tmp`, substitutes `RELAY_DOMAIN`, and runs `strfry --config`. (sed -i fails on bind mounts.)
  - Set `RELAY_DOMAIN` in `.env` or the shell; compose defaults to `custom-minipc.com`.
  - `deploy.sh` exports `RELAY_DOMAIN` (domain arg or public IP) and writes it to `.env`.
  - **Strfry image is `ghcr.io/hoytech/strfry:latest`** (official GHCR). The old `herrrring/strfry` image does NOT exist on Docker Hub — do not use it.
  - Strfry uses INI `strfry.conf`, NOT JSON. Config mounts to `/app/strfry.conf`; data volume mounts to `/app/strfry-db` (the image's default writable path, owned by UID 1000 `strfry`).

## Work Guidance

- Services target `linux/arm64` (Oracle Free Tier) or `linux/amd64` (x86_64 VPS) via the matching compose file
- Strfry relays are stateless (data in named volumes) for easy restore
- libp2p relay built from Go source in `libp2p-relay/` subdirectory (arch-agnostic Dockerfile via `TARGETARCH` build arg)
- ws-relay built from Go source in `ws-relay/` subdirectory (arch-agnostic Dockerfile via `TARGETARCH` build arg)
- Coturn uses `diamondburned/coturn:latest` image
- TURN credentials must be updated in both Docker config and Android `BuildConfig`
- No secrets committed to repo — use `.env` file for sensitive values

## Verification

- `docker compose -f docker-compose.yml ps` (ARM64) or `docker compose -f docker-compose.amd64.yml ps` (AMD64) from `infrastructure/`
- `docker compose -f <file> logs <service>` for per-service diagnostics
- Health checker container logs show OK/DOWN per endpoint every 60s

## Child DOX Index

| Subpath | Owner | Purpose |
|---------|-------|---------|
| `strfry/` | DevOps | Nostr relay JSON configuration files (3 data relays + 1 metadata relay) |
| `libp2p-relay/` | DevOps | Go source + Dockerfile for libp2p circuit relay v2 |
| `coturn/` | DevOps | Coturn TURN/STUN server configuration |
| `scripts/` | DevOps | Deploy, restart, status, and backup shell scripts |
