#!/usr/bin/env bash
# Builds Eclipse's built-in Tailscale (tunnel/) into an Android library
# (arm64 for phones; x86_64 only for the tests on the Android emulator):
#   scripts/build_tunnel.sh <output folder>   ->  <output folder>/eclipsenet.aar
set -euo pipefail
OUT=$(realpath -m "$1")
mkdir -p "$OUT"
cd "$(dirname "$0")/../tunnel"
go version
# The Go module servers sometimes cut a download: try again (3 times).
retry() { for i in 1 2 3; do "$@" && return 0; echo "retry ($i): $*"; sleep 20; done; return 1; }
retry go get tailscale.com@"$(cat TAILSCALE_VERSION)" golang.org/x/mobile/bind@latest
retry go mod tidy
LOG="${GITHUB_WORKSPACE:-/tmp}/tunnel-test.log"
: > "$LOG"
# On failure, the essential lines become an annotation (see the run page)
report() {
  grep -E -- "--- FAIL|FAIL|panic:|\.go:[0-9]+:|error|cannot|undefined" "$LOG" | head -25 | cut -c1-300 \
    | sed 's/%/%25/g' | awk 'BEGIN{printf "::error title=Tailscale intégré::"} {printf "%s%%0A", $0} END{print ""}'
  exit 1
}
go vet ./... >> "$LOG" 2>&1 || report
go test -count=1 ./... >> "$LOG" 2>&1 || report
ECLIPSE_E2E=1 go test -count=1 -v -run TestTailnetRelay . > /tmp/e2e.log 2>&1 || { cat /tmp/e2e.log >> "$LOG"; cp /tmp/e2e.log "${GITHUB_WORKSPACE:-/tmp}/tunnel-e2e.log"; report; }
cp /tmp/e2e.log "${GITHUB_WORKSPACE:-/tmp}/tunnel-e2e.log" || true
grep -E "^(--- |ok|PASS|FAIL)|tcp:|udp burst|video:|discover|phone status" /tmp/e2e.log || true
# The test PC on a private Tailscale network, for the emulator tests.
go test -c -o "${GITHUB_WORKSPACE:-/tmp}/fakepc.test" .
go install golang.org/x/mobile/cmd/gomobile@latest golang.org/x/mobile/cmd/gobind@latest
export PATH="$PATH:$(go env GOPATH)/bin"
export ANDROID_NDK_HOME="${ANDROID_NDK_LATEST_HOME:-$(ls -d "$ANDROID_HOME"/ndk/* | sort -V | tail -1)}"
echo "NDK: $ANDROID_NDK_HOME"
gomobile init
gomobile bind -target=android/arm64,android/amd64 -androidapi 23 -trimpath -ldflags="-s -w" -o "$OUT/eclipsenet.aar" .
ls -la "$OUT"
unzip -l "$OUT/eclipsenet.aar" | grep -E "\.so|classes.jar"
