#!/usr/bin/env bash
set -euo pipefail
PORT="${1:-19132}"
echo "Opening Minecraft Bedrock UDP ${PORT} on the Linux host firewall..."
if command -v ufw >/dev/null 2>&1; then
  sudo ufw allow "${PORT}/udp"
  sudo ufw reload
  sudo ufw status | grep -E "${PORT}(/udp)?" || true
elif command -v firewall-cmd >/dev/null 2>&1; then
  sudo firewall-cmd --permanent --add-port="${PORT}/udp"
  sudo firewall-cmd --reload
  sudo firewall-cmd --list-ports | tr ' ' '\n' | grep "^${PORT}/udp$" || true
else
  echo "Neither ufw nor firewalld was found. If another host firewall is active, allow UDP ${PORT} manually."
fi
cat <<MSG

For Internet play you ALSO need router/NAT forwarding:
  UDP ${PORT} -> this Linux machine's LAN IP -> UDP ${PORT}

The controller can verify Docker's local port publish, but not prove Internet reachability from inside the LAN.
MSG
