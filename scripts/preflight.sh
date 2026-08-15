#!/usr/bin/env bash
set -euo pipefail

PORT="${1:-${MC_HOST_PORT:-19132}}"
errors=0
warnings=0

ok()   { printf 'OK   %s\n' "$*"; }
warn() { printf 'WARN %s\n' "$*"; warnings=$((warnings+1)); }
fail() { printf 'FAIL %s\n' "$*"; errors=$((errors+1)); }

if command -v docker >/dev/null 2>&1; then
  ok "docker: $(docker --version 2>/dev/null || true)"
else
  fail "docker command not found"
fi

if docker compose version >/dev/null 2>&1; then
  ok "docker compose: $(docker compose version --short 2>/dev/null || docker compose version 2>/dev/null)"
else
  fail "Docker Compose v2 is not available"
fi

if [[ -S /var/run/docker.sock ]]; then
  if docker info >/dev/null 2>&1; then
    ok "Docker daemon is reachable"
  else
    fail "Docker socket exists but current user cannot reach the daemon"
  fi
else
  fail "/var/run/docker.sock not found"
fi

if command -v ss >/dev/null 2>&1; then
  if ss -H -lun 2>/dev/null | awk '{print $5}' | grep -Eq "(^|:)${PORT}$"; then
    warn "UDP ${PORT} is already bound. This is expected only if minecraft-wake is already running."
  else
    ok "UDP ${PORT} is currently free on the host"
  fi
else
  warn "ss not installed; skipped host UDP bind check"
fi

if command -v ufw >/dev/null 2>&1; then
  status="$(ufw status 2>/dev/null || true)"
  if grep -q '^Status: active' <<<"$status"; then
    if grep -Eq "(^|[[:space:]])${PORT}/udp([[:space:]]|$)" <<<"$status"; then
      ok "UFW has an allow rule for UDP ${PORT}"
    else
      warn "UFW is active but no obvious UDP ${PORT} allow rule was found; run scripts/open-bedrock-port.sh ${PORT}"
    fi
  else
    ok "UFW is not active"
  fi
elif command -v firewall-cmd >/dev/null 2>&1 && firewall-cmd --state >/dev/null 2>&1; then
  if firewall-cmd --query-port="${PORT}/udp" >/dev/null 2>&1; then
    ok "firewalld allows UDP ${PORT}"
  else
    warn "firewalld is active but UDP ${PORT} is not allowed; run scripts/open-bedrock-port.sh ${PORT}"
  fi
else
  warn "Neither active UFW nor firewalld was detected; verify any nftables/iptables/cloud firewall yourself"
fi

printf '\n'
printf 'INFO Public Internet reachability cannot be proven from this LAN host alone.\n'
printf 'INFO For remote friends, forward UDP %s on the router to this Linux host.\n' "$PORT"

if (( errors > 0 )); then
  printf '\nPreflight failed: %d error(s), %d warning(s).\n' "$errors" "$warnings" >&2
  exit 1
fi
printf '\nPreflight passed with %d warning(s).\n' "$warnings"
