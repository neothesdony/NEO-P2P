#!/usr/bin/env bash
# Regenerates the checked-in file-based Maven repo at android/thirdparty-repo
# from the PINNED RNS/LXMF fork commits (F1, 2026-09-23).
#
# The app no longer resolves these from mavenLocal()/jitpack — a clean checkout
# and CI resolve them straight from android/thirdparty-repo. Run this only to
# regenerate the repo after deliberately bumping a pin below, then commit the
# changed artifacts (and update android/gradle/libs.versions.toml).
#
# Fail-closed: if either pinned remote is unreachable the script exits non-zero.
# There is NO silent skip — a skipped LXMF build would leave a stale artifact.
#
# Pinned commits (update deliberately, then bump the version suffixes + the
# libs.versions.toml entries + this header):
#   reticulum-kt: 1a7f61937b2200aa98a646e592cd208b016d6277  (forgejo, neo-p2p-reconnect-backoff)
#   LXMF-kt:      74d343a00bcd5dbeeb9249c68dc6efb9f8454389  (forgejo)
set -euo pipefail

REPO_ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"
M2_REPO="$REPO_ROOT/android/thirdparty-repo"
WORK="${WORK:-$HOME/fork-builds}"
RETICULUM_REPO="http://192.168.200.121:3333/thesdony/reticulum-kt.git"
LXMF_REPO="http://192.168.200.121:3333/thesdony/LXMF-kt.git"
RETICULUM_PIN="1a7f61937b2200aa98a646e592cd208b016d6277"
LXMF_PIN="74d343a00bcd5dbeeb9249c68dc6efb9f8454389"
# Version suffix = first 8 chars of the pin.
RETICULUM_VERSION="0.1.0-${RETICULUM_PIN:0:8}"
LXMF_VERSION="0.1.0-${LXMF_PIN:0:8}"

require_remote() {
  local url="$1"
  if ! timeout 15 git ls-remote "$url" HEAD >/dev/null 2>&1; then
    echo "FATAL: remote unreachable: $url" >&2
    echo "Push the pinned commit to a reachable remote before regenerating." >&2
    exit 1
  fi
}

clone_pin() {
  local name="$1" url="$2" pin="$3"
  local dir="$WORK/$name"
  if [ ! -d "$dir/.git" ]; then
    git clone --quiet "$url" "$dir"
  fi
  git -C "$dir" fetch --quiet origin
  git -C "$dir" checkout --quiet "$pin"
}

require_remote "$RETICULUM_REPO"
require_remote "$LXMF_REPO"

rm -rf "$M2_REPO"
mkdir -p "$M2_REPO"

clone_pin "reticulum-kt" "$RETICULUM_REPO" "$RETICULUM_PIN"
clone_pin "LXMF-kt" "$LXMF_REPO" "$LXMF_PIN"

# 1) Publish the reticulum fork at its immutable version.
#    -Dmaven.repo.local redirects mavenLocal() at $M2_REPO, so the fork's
#    publishToMavenLocal writes the checked-in file repo.
(cd "$WORK/reticulum-kt" && \
  VERSION="$RETICULUM_VERSION" ./gradlew --no-daemon --quiet \
    -Dmaven.repo.local="$M2_REPO" \
    :rns-core:publishToMavenLocal :rns-interfaces:publishToMavenLocal)

# 2) The LXMF pin declares `rns-core:0.1.0-SNAPSHOT`, so stage the same rns-core
#    under that coordinate to let lxmf-core compile. Step 4 rewrites the
#    published metadata to the pinned coordinate and drops this staging.
(cd "$WORK/reticulum-kt" && \
  VERSION="0.1.0-SNAPSHOT" ./gradlew --no-daemon --quiet \
    -Dmaven.repo.local="$M2_REPO" \
    :rns-core:publishToMavenLocal :rns-interfaces:publishToMavenLocal)

# 3) Build + publish lxmf-core at its immutable version.
(cd "$WORK/LXMF-kt" && \
  VERSION="$LXMF_VERSION" LOCAL_RETICULUM_KT_VIA_MAVEN_LOCAL=1 \
  ./gradlew --no-daemon --quiet \
    -Dmaven.repo.local="$M2_REPO" \
    :lxmf-core:publishToMavenLocal)

# 4) Repoint the published lxmf-core metadata at the pinned rns-core version,
#    then remove the temporary SNAPSHOT staging + local metadata noise.
LXMF_DIR="$M2_REPO/com/github/torlando-tech/LXMF-kt/lxmf-core/$LXMF_VERSION"
sed -i "s/0\.1\.0-SNAPSHOT/$RETICULUM_VERSION/g" \
  "$LXMF_DIR/lxmf-core-$LXMF_VERSION.pom" \
  "$LXMF_DIR/lxmf-core-$LXMF_VERSION.module"
rm -rf "$M2_REPO/com/github/torlando-tech/reticulum-kt/rns-core/0.1.0-SNAPSHOT" \
       "$M2_REPO/com/github/torlando-tech/reticulum-kt/rns-interfaces/0.1.0-SNAPSHOT"
find "$M2_REPO" -name 'maven-metadata-local.xml' -delete

if grep -rq "0.1.0-SNAPSHOT" "$M2_REPO"; then
  echo "FATAL: SNAPSHOT coordinates remain in $M2_REPO" >&2
  exit 1
fi

echo "Fork repo regenerated at $M2_REPO (reticulum $RETICULUM_VERSION, lxmf $LXMF_VERSION)"
