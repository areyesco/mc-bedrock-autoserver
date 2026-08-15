# Security notes

## Trust boundaries

The stack deliberately avoids giving the Java control service or Lazytainer the raw host Docker socket.

- Only `docker-api-proxy` mounts `/var/run/docker.sock`.
- HAProxy permits only Docker ping/version, container list/inspect, and start/stop. Everything else returns HTTP 403.
- Java reaches that filtered proxy on an `internal: true` Docker network that is not attached to the Bedrock/wake network namespace.
- Lazytainer reaches the same filtered proxy through `/run/docker-api/docker.sock`, a **proxy-created Unix socket in a named volume**.
- The named volume is mounted into Lazytainer but not into `minecraft-bedrock`. Docker `network_mode: service:minecraft-wake` shares networking, not filesystems/volumes, so Bedrock does not inherit the Docker API socket.
- The only raw Docker socket holder is the tiny HAProxy boundary container. It runs as root only because the official HAProxy image otherwise cannot open a typical root-owned Docker socket and because it must create the private Unix socket.
- The local Java REST port is host-bound to `127.0.0.1` only.
- When `MCP_SHARED_TOKEN` is configured, `/mcp` requires `X-Minecraft-MCP-Token`; the OpenAI tunnel client injects the header on the private MCP hop.
- Secure MCP Tunnel is outbound-only from this host, so MCP does not require a public inbound listener. Minecraft UDP 19132 remains the intended public inbound service.

## Why the Docker socket mount is still sensitive

Mounting `/var/run/docker.sock:ro` does **not** make the Docker API read-only; Unix socket filesystem mode and HTTP API authorization are different things. The HAProxy ACL is the capability boundary. Treat anyone able to edit this Compose project or `haproxy.cfg` as a host administrator.

The proxy intentionally cannot create containers, exec commands, attach arbitrary volumes, pull images, create privileged workloads, or modify networks. Its permitted mutating surface is only `POST .../start` and `POST .../stop` for already-existing containers.

## MCP identity and authorization

`MCP_SHARED_TOKEN` authenticates the tunnel sidecar to the local MCP endpoint; it does **not** identify a human ChatGPT user. Human access should be controlled in OpenAI using tunnel organization/workspace associations and the relevant ChatGPT developer-mode/app permissions. Keep the shared token out of source control.

Use `scripts/mcp-up.sh` rather than starting the MCP profile blindly; it refuses to launch the profile when the tunnel ID, runtime key, or shared token is missing.

## Internet UDP reachability

The Java service can verify:

- Docker API access,
- wake/BDS container states,
- whether Docker reports UDP 19132 published,
- Bedrock RakNet responsiveness and live advertised player count while BDS runs.

It cannot truthfully prove that your ISP/router/NAT/CGNAT/firewall accepts UDP from the public Internet while testing from the same LAN. That needs a probe from a genuinely external network. The app reports external reachability as `UNKNOWN` rather than producing a false positive.

## Operational recommendations

- Keep `ONLINE_MODE=true`.
- Use Bedrock's allowlist for known Xbox/Microsoft gamertags.
- Back up `data/` before image/BDS upgrades.
- After initial validation, pin third-party image digests in `.env` if you want maximum reproducibility.
- Keep Docker Engine and the host OS patched.
- Do not publish the Java control port or Docker API proxy port to the Internet.
- Rotate `MCP_SHARED_TOKEN` and the OpenAI tunnel runtime key if either is exposed.

## Audit scope

The included review is an architecture/code/configuration review and targeted upstream/version check, not a formal penetration test or complete software-composition analysis of every transitive dependency and container layer. Run your normal SCA/container scanner (for example, the one already used in your environment) before treating the stack as production hardened.
