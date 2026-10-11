#!/usr/bin/env bash
# Runs a command; if it fails, turns the end of its output into a GitHub
# annotation, so the reason is visible on the run page (and through the API).
title="$1"; shift
log=$(mktemp)
"$@" 2>&1 | tee "$log"
code=${PIPESTATUS[0]}
if [ "$code" -ne 0 ]; then
  msg=$(grep -v '^\s*$' "$log" | tail -40 | sed 's/%/%25/g' | awk '{printf "%s%%0A", $0}')
  echo "::error title=${title} (code ${code})::${msg}"
fi
exit "$code"
