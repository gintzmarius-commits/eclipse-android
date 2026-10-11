#!/usr/bin/env bash
# Builds Eclipse's built-in Tailscale (tunnel/) into an Android library
# (arm64 for phones; x86_64 only for the tests on the Android emulator):
#   scripts/build_tunnel.sh <output folder>   ->  <output folder>/eclipsenet.aar
set -euo pipefail
OUT=$(realpath -m "$1")
mkdir -p "$OUT"
cd "$(dirname "$0")/../tunnel"
go version
go get tailscale.com@"$(cat TAILSCALE_VERSION)" golang.org/x/mobile/bind@latest
go mod tidy
go vet ./...
go test -count=1 ./...
ECLIPSE_E2E=1 go test -count=1 -v -run TestTailnetRelay . > /tmp/e2e.log 2>&1 || { tail -40 /tmp/e2e.log; exit 1; }
cp /tmp/e2e.log "${GITHUB_WORKSPACE:-/tmp}/tunnel-e2e.log" || true
grep -E "^(--- |ok|PASS|FAIL)|tcp:|udp burst|video:|discover|phone status" /tmp/e2e.log || true
go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
export PATH="$PATH:$(go env GOPATH)/bin"
export ANDROID_NDK_HOME="${ANDROID_NDK_LATEST_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
echo "NDK: $ANDROID_NDK_HOME"
gomobile init
gomobile bind -target=android/arm64,android/amd64 -androidapi 23 -trimpath -ldflags="-s -w" -o "$OUT/eclipsenet.aar" .
ls -la "$OUT"
unzip -l "$OUT/eclipsenet.aar" | grep -E "\.so|classes.jar"
