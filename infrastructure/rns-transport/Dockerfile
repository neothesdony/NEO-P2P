# NEO-P2P RNS Transport Node
#
# Runs the official Python rnsd (Reticulum Network Stack Daemon) with
# enableTransport=true and a TCP server interface. Phones connect as TCP
# clients; the node routes announces, paths, and links between peers and to
# the LXMF propagation node.
#
# Why Python rnsd instead of rnsd-kt (the Kotlin fork):
#   - Python is the reference implementation. The two fork fixes that were
#     needed in rnsd-kt on 2026-08-31 are native behavior here:
#       * spawned-client registration: TCPServerInterface.incoming_connection
#         calls Transport.add_interface() for every accepted client
#       * receiving-interface wiring: each spawned client is its own
#         TCPClientInterface whose read_loop feeds Transport.inbound with the
#         child ref (parent process_outgoing is a no-op)
#   - The Kotlin client (RnsSession) is already validated against Python
#     behavior via the conformance bridge (WireTcp.kt:877).
#
# The config is bind-mounted from the host (config + storage persist across
# restarts). rnsd loads exactly File(dir, "config") — no extension.

FROM python:3.11-slim

# Unbuffered stdout: rnsd logs to stdout, and Python block-buffers when stdout
# is a pipe (docker logs) — without this, `docker logs` stays empty until the
# buffer fills or the process exits.
ENV PYTHONUNBUFFERED=1

# lxmf is REQUIRED at runtime: the TCP server interface auto-configures to
# gateway mode and enables on-network interface discovery, which hard-panics
# (RNS.panic) if the LXMF module is missing — the container crash-loops.
RUN pip install --no-cache-dir rns lxmf

WORKDIR /opt/rnsd

# Config dir is mounted from the host (config + storage persist across restarts).
VOLUME /etc/reticulum

EXPOSE 42000

# Raw TCP probe — port 42000 is a Reticulum interface, not HTTP (curl's
# /health can never succeed there). python:3.11-slim has no bash, so use a
# python socket check (same pattern as the lxmf-propagation container).
HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD python3 -c "import socket; socket.create_connection(('127.0.0.1', 42000), 5)" || exit 1

ENTRYPOINT ["rnsd", "--config", "/etc/reticulum", "-v"]
