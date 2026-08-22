#!/bin/sh
# NEO-P2P strfry entrypoint
# Substitutes the RELAY_DOMAIN env var into the config at container start,
# so the advertised relay URL is not hardcoded. Falls back to a sensible
# default if RELAY_DOMAIN is not set.

set -e

# Default to the placeholder domain if not provided
RELAY_DOMAIN="${RELAY_DOMAIN:-relay.custom-minipc.com}"

# The mounted config is a bind mount, so we can't sed -i in place.
# Copy it to a writable temp path, substitute, then point strfry at it.
SRC_CONFIG="/app/strfry.conf"
WORK_CONFIG="/tmp/strfry.conf"

if [ -f "$SRC_CONFIG" ]; then
    cp "$SRC_CONFIG" "$WORK_CONFIG"
    # Replace the __RELAY_DOMAIN__ placeholder with the actual domain
    sed -i "s|__RELAY_DOMAIN__|${RELAY_DOMAIN}|g" "$WORK_CONFIG"
    echo "strfry: configured relay domain = ${RELAY_DOMAIN}"
else
    echo "strfry: WARNING no config at $SRC_CONFIG, using defaults"
    WORK_CONFIG=""
fi

# Launch strfry relay with the config
if [ -n "$WORK_CONFIG" ]; then
    exec /app/strfry --config="$WORK_CONFIG" relay "$@"
else
    exec /app/strfry relay "$@"
fi
