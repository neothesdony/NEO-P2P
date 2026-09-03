# Server Infrastructure — infrastructure/

## Purpose

Server-side deployment infrastructure for NEO-P2P. Runs on Oracle Cloud Free Tier (ARM64, 4 cores, 24GB RAM). Phase 4 (2026-08-31): the Nostr relays (strfry x4), libp2p circuit relay, WS relay, and coturn were **removed** — replaced by the RNS transport node + LXMF propagation node.

## Ownership

- **Owner:** DevOps
- **Scope:** `infrastructure/` — `docker-compose.yml`, `rns-transport/` (official Python rnsd transport node), `lxmf-propagation/` (Python lxmd propagation node), deployment/management scripts

## Local Contracts

- **Docker Compose stack:**
  - 1× `rns-transport` — **official Python rnsd** (Reticulum Network Stack Daemon, `pip install rns lxmf`), `enableTransport=true`, TCP server on 42000. Phones connect as TCP clients; the node routes announces, paths, and links between peers and to the propagation node. **Replaced the rnsd-kt fork on 2026-09-01** — the two fork fixes that were needed in rnsd-kt (spawned-client registration + receiving-interface wiring) are native Python behavior; `rnsd-kt.jar` remains in the dir as dead weight.
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
- **rns-transport image:** `python:3.11-slim` + `pip install rns lxmf` (lxmf is a HARD runtime dep — the TCP server interface auto-configures to gateway mode and hard-panics without the LXMF module). `PYTHONUNBUFFERED=1` so `docker logs` flows. HEALTHCHECK is a python socket probe (slim image has no bash; 42000 is a raw Reticulum interface, not HTTP).
- **rns-transport config:** `rns-transport/config` (rnsd loads exactly `File(dir, "config")` — no extension). Mounted read-only into the container at `/etc/reticulum/config:ro` (the `rns-transport-data` named volume holds only the persistent identity/state; a fresh volume no longer orphans the config). Do NOT rename it back to `config.yml` — rnsd will silently start with zero interfaces and `transport=disabled` (the 2026-08-31 outage: phones connected to docker-proxy's 42000 with a dead backend and hit EOF every ~14-33s).
- **Spawned-client registration (native Python behavior since 2026-09-01):** `TCPServerInterface.incoming_connection` calls `Transport.add_interface()` for every accepted client (deregistered symmetrically on disconnect). Without this, announces were never fanned out to other clients and `nextHopInterface()` could not resolve a client's path — no peer discovery, no data routing between phones. Verify after a client connects: log shows `Registered interface: VPS TCP Server/client-N` and announces queue on `client-1`/`client-2` (not only `Propagation Link`).
- **Receiving-interface wiring (native Python behavior since 2026-09-01):** each spawned client is its own `TCPClientInterface` whose `read_loop` feeds `Transport.inbound` with the child ref (the parent's `process_outgoing` is a no-op). The rnsd-kt fork wired `onPacketReceived` with the PARENT ref, so LINKREQUESTs between phones were silently dropped (`Transport forwarding LINKREQUEST ... via VPS TCP Server` in the server log, `Link establishment timed out` + `LXMF delivery failed (offer_request)` on the phone). Verify: server log shows `Transport forwarding LINKREQUEST ... via VPS TCP Server/client-N` (child, not parent) and the peer's link establishes.
- **Propagation node storage:** LXMF caps are PROPAGATION_LIMIT=256 messages, DELIVERY_LIMIT=1000, MESSAGE_EXPIRY=30 days. The lxmd entrypoint mounts a persistent volume and runs a daily prune (drop `.msg` files older than 30 days; guarded — `python:3.11-slim` has no cron, so the prune is skipped when `/etc/periodic/daily` is absent).
- **Announce rate limiter (2026-09-02, REQUIRED on every node serving NEO-P2P):** the Python rnsd default `announce_rate_target = 3600` (min 1 announce/hour per destination hash, grace 5, then a 1-hour block) silently kills the app's feed — phones announce delivery 1/20s + offers 12/30s, so their destinations get blocked ~6 announces after connecting ("Blocking rebroadcast ... due to excessive announce rate" in the node log). Every interface section MUST set `announce_rate_target = 1`, `announce_rate_grace = 20`, `announce_rate_penalty = 0` (spawned client interfaces inherit the parent's values). The app's pacing (2.5s offer tick, 20s delivery, tombstone 1/4 ticks ≈ 15/30s per dest) then passes cleanly. A node without these settings is NOT compatible with the app — public nodes are best-effort, our own node is the supported transport.

## Work Guidance

- Services target `linux/arm64` (Oracle Free Tier) or `linux/amd64` (x86_64 VPS) via the matching compose file
- The RNS transport node identity + config persist in the `rns-transport-data` volume (`/etc/reticulum`) — never delete it or peers lose the cached path. The `config` file is bind-mounted `:ro` from the repo; only the identity + destination cache live in the volume.
- The propagation node identity persists in `lxmf-propagation-data` (`/var/lib/lxmf/identity`) — must be STABLE across restarts (peers cache the node's destination hash)
- The transport node's `config` defines the `[[Propagation Link]]` TCPClientInterface → `lxmf-propagation:42000` (docker DNS). Keep this in sync with the compose service name.
- No secrets committed to repo — use `.env` file for sensitive values

## Verification

- `docker compose -f docker-compose.yml ps` (ARM64) or `docker compose -f docker-compose.amd64.yml ps` (AMD64) from `infrastructure/` — both services should read `Up (healthy)`
- `docker compose -f <file> logs <service>` for per-service diagnostics:
  - rns-transport must show `transport=enabled`, `[VPS TCP Server] Listening on 0.0.0.0:42000`, `Registered interface: VPS TCP Server/client-N` per connected phone, and announces queued on every client (peer discovery fan-out)
  - lxmf-propagation must show `LXMF Propagation Node started on ...` (no crash loop)
- Health checker container logs show OK/DOWN per endpoint every 60s
- Live: `nc -z 127.0.0.1 42000` from the VPS; phones connect to `relay1.custom-minipc.com:42000`
- Phone-side: logcat shows `[RnsSession] Peer seen: <peerId>` when a peer's `lxmf.delivery` announce arrives through the transport node

## Child DOX Index

| Subpath | Owner | Purpose |
|---------|-------|---------|
| `rns-transport/` | DevOps | Python rnsd transport node (Dockerfile + `config` — note: no `.yml` suffix; `rnsd-kt.jar` is dead weight) |
| `lxmf-propagation/` | DevOps | Python lxmd propagation node (Dockerfile + lxmd.sh) |
| `scripts/` | DevOps | Deploy, restart, status, and backup shell scripts |
