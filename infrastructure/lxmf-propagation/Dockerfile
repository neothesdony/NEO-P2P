# NEO-P2P LXMF Propagation Node
#
# Store-and-forward for offline peers (replaces the WS relay's offline queue
# and the Nostr relays' durable bus). The Kotlin lxmf-core fork is client-only
# for propagation (no /get request server), so this node runs the reference
# Python LXMF propagation node (lxmd) — the Kotlin client interops with it
# (see LXMF-kt PropagationSyncTest).
#
# The node announces the lxmf.propagation aspect; peers discover it via the
# RNS transport node and sync messages on reconnect.

FROM python:3.11-slim

RUN pip install --no-cache-dir rns lxmf

WORKDIR /opt/lxmf

# lxmd entrypoint: start the propagation node with a persistent identity +
# storage. The identity must be STABLE across restarts (peers cache the node's
# destination hash) — it is generated on first boot and persisted in the volume.
COPY lxmd.sh /opt/lxmf/lxmd.sh
RUN chmod +x /opt/lxmf/lxmd.sh

VOLUME /var/lib/lxmf

EXPOSE 42000

HEALTHCHECK --interval=30s --timeout=10s --retries=3 \
    CMD python3 -c "import socket; socket.create_connection(('127.0.0.1', 42000), 5)" || exit 1

ENTRYPOINT ["/opt/lxmf/lxmd.sh"]
