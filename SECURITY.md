# Security notes

## Trust boundaries

The stack deliberately avoids giving the Java control service or Lazytainer the raw host Docker socket.

- Only `docker-api-proxy` mounts `/var/run/docker.sock`.
- HAProxy exposes **two different filtered frontends**: a TCP frontend for Java and a Unix-socket frontend for Lazytainer.
- The Java frontend permits Docker ping/version, container list/inspect, start/stop for the existing `minecraft-bedrock` container, restart for the existing `minecraft-wake` container, and the minimum Docker exec endpoints needed to operate **only the Bedrock container**.
- The Java Docker-exec capability is used by a fixed Bedrock console-command bridge and for fixed reads of `/data/server.properties` and `/data/allowlist.json`. The bridge matches only the Bedrock process, drops to its UID/GID and writes the command to its stdin. MCP input is never interpolated into a shell command.
- Lazytainer's Unix-socket frontend retains only ping/version, container list/inspect, and start/stop. It **cannot** create, start, or inspect Docker exec sessions.
- Java reaches its filtered proxy frontend on an `internal: true` Docker network that is not attached to the Bedrock/wake network namespace.
- Lazytainer reaches its filtered frontend through `/run/docker-api/docker.sock`, a **proxy-created Unix socket in a named volume**.
- The named volume is mounted into Lazytainer but not into `minecraft-bedrock`. Docker `network_mode: service:minecraft-wake` shares networking, not filesystems/volumes, so Bedrock does not inherit the Docker API socket.
- The only raw Docker socket holder is the tiny HAProxy boundary container. It runs as root only because the official HAProxy image otherwise cannot open a typical root-owned Docker socket and because it must create the private Unix socket.
- The local Java REST port is host-bound to `127.0.0.1` only.
- When `MCP_SHARED_TOKEN` is configured, `/mcp` requires `X-Minecraft-MCP-Token`; the OpenAI tunnel client injects the header on the private MCP hop.
- Secure MCP Tunnel is outbound-only from this host, so MCP does not require a public inbound listener. Minecraft UDP 19132 remains the intended public inbound service.

## Why the Docker socket mount is still sensitive

Mounting `/var/run/docker.sock:ro` does **not** make the Docker API read-only; Unix socket filesystem mode and HTTP API authorization are different things. The HAProxy ACL is the capability boundary. Treat anyone able to edit this Compose project or `haproxy.cfg` as a host administrator.

The proxy still cannot create containers, attach arbitrary volumes, pull images, create privileged workloads, or modify networks. The Java frontend has deliberately narrow additional capabilities: it can create Docker exec sessions only in the pre-existing `minecraft-bedrock` container and restart only `minecraft-wake` to clear Lazytainer's in-memory packet history after a stop. A compromise of `minecraft-control` should therefore be treated as potential command execution **inside the Bedrock container** and denial of the wake listener, but it does not grant the application a raw Docker socket or the ability to create a privileged container or mount host paths.

The MCP allowlist implementation further constrains normal use: the player-supplied gamertag is passed as one argument to the fixed console-command bridge for `allowlist add/remove`. Fixed shell commands are used only for that bridge and to read known files under `/data`; user input is not included in those shell strings.

## MCP identity and authorization

`MCP_SHARED_TOKEN` authenticates the tunnel sidecar to the local MCP endpoint; it does **not** identify a human ChatGPT user. Human access should be controlled in OpenAI using tunnel organization/workspace associations and the relevant ChatGPT developer-mode/plugin permissions. Keep the shared token out of source control.

Allowlist add/remove are mutating MCP tools. Anyone authorized to invoke those tools can grant or revoke Minecraft access for a gamertag, so restrict access to the custom ChatGPT plugin accordingly.

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
- Keep `MC_ALLOW_LIST=true` and grant access only to known Xbox/Microsoft gamertags.
- Back up `data/` before image/BDS upgrades.
- After initial validation, pin third-party image digests in `.env` if you want maximum reproducibility.
- Keep Docker Engine and the host OS patched.
- Do not publish the Java control port or Docker API proxy port to the Internet.
- Rotate `MCP_SHARED_TOKEN` and the OpenAI tunnel runtime key if either is exposed.

## Audit scope

The included review is an architecture/code/configuration review and targeted upstream/version check, not a formal penetration test or complete software-composition analysis of every transitive dependency and container layer. Run your normal SCA/container scanner (for example, the one already used in your environment) before treating the stack as production hardened.
