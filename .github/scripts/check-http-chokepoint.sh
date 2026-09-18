#!/usr/bin/env bash
# Architectural gate (P5/P6.2): ChainMonitor + AppModule are the ONLY places
# an HTTP client may be constructed. Every network call must go through the
# injected, failover-hardened Ktor client so a new bespoke client cannot bypass
# the explorer allow-list / fail-closed behaviour. ChainMonitor now lives in
# :core, so both module main-source trees are scanned.
set -euo pipefail

repo_root="$(cd "$(dirname "$0")/../.." && pwd)"
src_dirs=(
  "$repo_root/android/app/src/main/java"
  "$repo_root/android/core/src/main/kotlin"
  "$repo_root/android/admind/src/main/kotlin"
)

# Files allowed to mention HTTP client construction.
allowlist_re='(di/AppModule\.kt|data/escrow/ChainMonitor\.kt):[0-9]+:'

violations="$(grep -RInE 'OkHttpClient|HttpClient\(|HttpURLConnection|java\.net\.URL' "${src_dirs[@]}" --include='*.kt' 2>/dev/null \
  | grep -vE "$allowlist_re" || true)"

if [ -n "$violations" ]; then
  echo "::error::HTTP client constructed outside the chokepoint (AppModule/ChainMonitor):"
  echo "$violations"
  exit 1
fi

echo "OK: single HTTP chokepoint respected"
