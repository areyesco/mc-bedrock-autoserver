#!/usr/bin/env bash
set -euo pipefail

if [[ ! -f .env ]]; then
  echo "Missing .env. Copy .env.example to .env first." >&2
  exit 1
fi

read_env() {
  local key="$1"
  local line value
  line="$(grep -E "^[[:space:]]*${key}[[:space:]]*=" .env | tail -n 1 || true)"
  [[ -n "$line" ]] || return 0
  value="${line#*=}"
  value="${value#${value%%[![:space:]]*}}"
  value="${value%${value##*[![:space:]]}}"
  if [[ "$value" == \"*\" && "$value" == *\" ]]; then value="${value:1:${#value}-2}"; fi
  if [[ "$value" == \'*\' && "$value" == *\' ]]; then value="${value:1:${#value}-2}"; fi
  printf '%s' "$value"
}

MCP_SHARED_TOKEN="$(read_env MCP_SHARED_TOKEN)"
CONTROL_PLANE_API_KEY="$(read_env CONTROL_PLANE_API_KEY)"
CONTROL_PLANE_TUNNEL_ID="$(read_env CONTROL_PLANE_TUNNEL_ID)"

missing=0
for key in MCP_SHARED_TOKEN CONTROL_PLANE_API_KEY CONTROL_PLANE_TUNNEL_ID; do
  if [[ -z "${!key:-}" ]]; then
    echo "Missing required MCP setting in .env: ${key}" >&2
    missing=1
  fi
done
[[ "$missing" -eq 0 ]] || exit 1

if [[ ${#MCP_SHARED_TOKEN} -lt 32 ]]; then
  echo "MCP_SHARED_TOKEN is too short; use at least 32 random characters (recommended: openssl rand -hex 32)." >&2
  exit 1
fi

docker compose --profile mcp up -d --build

echo "MCP profile started. Follow logs with: docker compose logs -f openai-tunnel minecraft-control"
