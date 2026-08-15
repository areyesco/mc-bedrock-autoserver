#!/usr/bin/env bash
set -euo pipefail
PORT="${CONTROL_HOST_PORT:-}"
if [[ -z "$PORT" && -f .env ]]; then
  PORT="$(grep -E '^[[:space:]]*CONTROL_HOST_PORT[[:space:]]*=' .env | tail -n1 | cut -d= -f2- | tr -d '[:space:]' || true)"
fi
PORT="${PORT:-8080}"
curl -fsS "http://127.0.0.1:${PORT}/api/status"; echo
