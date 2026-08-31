# Server Infrastructure — infrastructure/

## Purpose

Server-side deployment infrastructure for NEO-P2P. Runs on Oracle Cloud Free Tier (ARM64, 4 cores, 24GB RAM). Phase 4 (2026-08-31): the Nostr relays (strfry x4), libp2p circuit relay, WS relay, and coturn were **removed** — replaced by the RNS transport node + LXMF propagation node.

## Ownership

- **Owner:** DevOps
- **Scope:** `infrastructure/` — `docker-compose.yml`, `rns-transport/` (rnsd-kt transport node), `lxmf-propagation/` (Python lxmd propagation node), deployment/management scripts

## Local Contracts

- **Docker Compose stack:**
  - 1× `rns-transport` — rnsd-kt (Kotlin Reticulum daemon), `enableTransport=true`, TCP server on 42000. Phones connect as TCP clients; the node routes announces, paths, and links between peers and to the propagation node.
  - 1× `lxmf-propagation` — Python `lxmd` propagation node (store-and-forward for offline peers, replaces the WS relay's offline queue + the Nostr relays' durable bus). The Kotlin lxmf-core fork is **client-only** for propagation (no `/get` request server) — the Python node is the reference implementation the Kotlin client interops with (see LXMF-kt `PropagationSyncTest`).
  - 1× Alpine-based health checker container
- **Compose files:**
  - `docker-compose.yml` — **ARM64** (Oracle Cloud Free Tier)
  - `docker-compose.amd64.yml` — **AMD64/x86_64** (any x86_64 VPS)
  - `deploy.sh` auto-detects host arch (`uname -m`) and selects the matching file
- **Scripts** (`scripts/`):
  - `deploy.sh` — Deploy full stack
  - `restart.sh` — Restart services
  - `status.sh` — Check service health
  - `backup.sh` — Backup RNS config + propagation store
- **Networking:** Docker bridge network `neo-p2p` (172.20.0.0/24); port 42000/tcp exposed for the RNS transport node
- **rnsd-kt jar:** built from `~/reticulum-kt` (`:rns-cli:shadowJar`) and copied to `rns-transport/rnsd-kt.jar` — **not committed** (build artifact). The Dockerfile expects it present at build time.
- **Propagation node storage:** LXMF caps are PROPAGATION_LIMIT=256 messages, DELIVERY_LIMIT=1000, MESSAGE_EXPIRY=30 days. The lxmd entrypoint mounts a persistent volume and runs a daily prune (drop `.msg` files older than 30 days).

## Work Guidance

- Services target `linux/arm64` (Oracle Free Tier) or `linux/amd64` (x86_64 VPS) via the matching compose file
- The RNS transport node identity + config persist in the `rns-transport-data` volume (`/etc/reticulum`) — never delete it or peers lose the cached path
- The propagation node identity persists in `lxmf-propagation-data` (`/var/lib/lxmf/identity`) — must be STABLE across restarts (peers cache the node's destination hash)
- No secrets committed to repo — use `.env` file for sensitive values

## Verification

- `docker compose -f docker-compose.yml ps` (ARM64) or `docker compose -f docker-compose.amd64.yml ps` (AMD64) from `infrastructure/`
- `docker compose -f <file> logs <service>` for per-service diagnostics
- Health checker container logs show OK/DOWN per endpoint every 60s
- Live: `nc -z 127.0.0.1 42000` from the VPS; phones connect to `relay1.custom-minipc.com:42000`

## Child DOX Index

| Subpath | Owner | Purpose |
|---------|-------|---------|
| `rns-transport/` | DevOps | rnsd-kt transport node (Dockerfile + config.yml) |
| `lxmf-propagation/` | DevOps | Python lxmd propagation node (Dockerfile + lxmd.sh) |
| `scripts/` | DevOps | Deploy, restart, status, and backup shell scripts |
