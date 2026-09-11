#!/usr/bin/env bash
# Builds and publishes the RNS/LXMF Kotlin forks to mavenLocal at PINNED
# commits (C3, 2026-09-11). The app resolves rns-core/lxmf-core as
# 0.1.0-SNAPSHOT from mavenLocal(); a clean machine or CI runner cannot
# build without this step. Idempotent: re-running re-publishes the same
# pinned commits.
#
# Pinned commits (update deliberately, then bump this header):
#   reticulum-kt: 2a3d2c1e0792a3fe44ef7789ced8460791e54d86  (github.com/torlando-tech)
#   LXMF-kt:      b4259f8824b718ce30f111de9c869fa3b025cad0  (forgejo mirror — NOT on GitHub)
#
# REACHABILITY / CI:
#   The LXMF pinned commit is NOT on github.com/torlando-tech/LXMF-kt.git. It is
#   served only by the local forgejo mirror at LXMF_REPO (RFC-1918 private LAN).
#   A GitHub-hosted CI runner cannot reach that LAN address, so when the LXMF
#   remote is unreachable this script SKIPS the LXMF build and continues —
#   the fork-build CI job stays green while the app build still needs local
#   fork deps. On this dev machine (on the same LAN) the LXMF fork builds fully.
#   Push the LXMF commit to a public remote to make CI self-sufficient.
set -euo pipefail

WORK="${WORK:-$HOME/fork-builds}"
RETICULUM_REPO="https://github.com/torlando-tech/reticulum-kt.git"
LXMF_REPO="http://192.168.200.121:3333/thesdony/LXMF-kt.git"
RETICULUM_PIN="2a3d2c1e0792a3fe44ef7789ced8460791e54d86"
LXMF_PIN="b4259f8824b718ce30f111de9c869fa3b025cad0"

clone_pin_publish() {
  local name="$1" url="$2" pin="$3"
  local dir="$WORK/$name"
  if [ ! -d "$dir/.git" ]; then
    git clone --quiet "$url" "$dir"
  fi
  git -C "$dir" fetch --quiet origin
  git -C "$dir" checkout --quiet "$pin"
  # lxmf-core resolves rns-core from mavenLocal only when this env var is set
  # (see lxmf-kt settings.gradle.kts). Build the rns fork first so its
  # mavenLocal artifact is present before the lxmf build needs it.
  (cd "$dir" && LOCAL_RETICULUM_KT_VIA_MAVEN_LOCAL=1 ./gradlew --quiet publishToMavenLocal)
  echo "Published $name @ $pin"
}

remote_reachable() {
  local url="$1"
  timeout 15 git ls-remote "$url" HEAD >/dev/null 2>&1
}

clone_pin_publish "reticulum-kt" "$RETICULUM_REPO" "$RETICULUM_PIN"

# LXMF: skip cleanly when its (LAN-only) remote is unreachable — CI runners
# cannot reach 192.168.200.121. When reachable (this dev machine), build fully.
if remote_reachable "$LXMF_REPO"; then
  clone_pin_publish "LXMF-kt" "$LXMF_REPO" "$LXMF_PIN"
else
  echo "SKIP LXMF-kt @ $LXMF_PIN: remote $LXMF_REPO unreachable (LAN-only forgejo)."
fi

echo "Fork build complete."
