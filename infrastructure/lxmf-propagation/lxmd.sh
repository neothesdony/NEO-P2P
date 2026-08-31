#!/bin/sh
# LXMF propagation node entrypoint.
#
# The Python lxmd propagation node needs:
#   - a stable identity (persisted in the volume) so peers' cached node hash
#     stays valid across restarts
#   - a TCP server interface on 42000 so the RNS transport node (and phones
#     via it) can reach it
#   - a pruning policy: LXMF caps are PROPAGATION_LIMIT=256 messages,
#     DELIVERY_LIMIT=1000, MESSAGE_EXPIRY=30 days. A daily prune drops
#     expired messages so the volume never grows unbounded.
set -e

CONFIG_DIR=/var/lib/lxmf
mkdir -p "$CONFIG_DIR"

# Stable identity: generate once, reuse forever.
if [ ! -f "$CONFIG_DIR/identity" ]; then
  python3 -c "
from RNS import Identity
import sys
Identity().to_file('$CONFIG_DIR/identity')
print('Generated propagation node identity')
"
fi

# RNS config: TCP server interface on 42000, transport enabled (the node
# also routes for the phones that connect through it).
cat > "$CONFIG_DIR/config" <<EOF
[reticulum]
enable_transport = Yes
share_instance = No

[interfaces]

  [[Propagation TCP Server]]
    type = TCPServerInterface
    enabled = Yes
    listen_ip = 0.0.0.0
    listen_port = 42000
EOF

# Daily prune: drop messages older than 30 days (LXMF MESSAGE_EXPIRY).
cat > /etc/periodic/daily/lxmf-prune <<'PRUNE'
#!/bin/sh
find /var/lib/lxmf -name "*.msg" -mtime +30 -delete 2>/dev/null || true
PRUNE
chmod +x /etc/periodic/daily/lxmf-prune

# Start the propagation node (lxmd from the lxmf package).
exec lxmd --config "$CONFIG_DIR" --identity "$CONFIG_DIR/identity"
