#!/usr/bin/env bash
# Enforces the committed NETWORK invariant:
#   - branch `main`        -> testnet
#   - every other branch    -> mainnet
# Usage: check-network-invariant.sh [branch]   (falls back to CI env vars)
set -euo pipefail

branch="${1:-${GITHUB_REF_NAME:-}}"
if [[ -z "$branch" && -n "${GITHUB_BASE_REF:-}" ]]; then
  branch="$GITHUB_BASE_REF"
fi
if [[ -z "$branch" ]]; then
  echo "No branch in context; skipping NETWORK invariant check"
  exit 0
fi

value="$(grep -oE 'buildConfigField\("String", "NETWORK", "\\"[a-z]+\\""\)' \
  android/app/build.gradle.kts | grep -oE '(mainnet|testnet)' | head -1)"

expected="mainnet"
if [[ "$branch" == "main" ]]; then
  expected="testnet"
fi

if [[ "$value" != "$expected" ]]; then
  echo "NETWORK invariant violated on '$branch': expected '$expected', found '${value:-<none>}'"
  exit 1
fi
echo "NETWORK invariant OK on '$branch': $value"
