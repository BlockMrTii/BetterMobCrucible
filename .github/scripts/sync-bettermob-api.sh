#!/usr/bin/env bash
# Replaces the bundled BetterMob API (compile-only copy) with the one from BetterMob's dev branch.
# Usage: .github/scripts/sync-bettermob-api.sh [ref]   (default: dev)
set -euo pipefail

ref="${1:-${BETTERMOB_REF:-dev}}"
api="src/main/java/eu/northsoft/bettermob/api"
tmp="$(mktemp -d)"
trap 'rm -rf "$tmp"' EXIT

git clone --quiet --depth 1 --branch "$ref" --filter=blob:none --sparse \
  https://github.com/HyperGaming99/bettermob "$tmp/bettermob"
git -C "$tmp/bettermob" sparse-checkout set "$api"

rm -rf "$api"
mkdir -p "$api"
cp -r "$tmp/bettermob/$api/." "$api/"
echo "BetterMob API synced from '$ref' ($(git -C "$tmp/bettermob" rev-parse --short HEAD))"
