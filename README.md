# Minecraft Bedrock Auto-Wake + Player-Aware Sleep + MCP

A self-hosted Docker Compose stack for **Minecraft Bedrock** (including Android clients) that wakes on connection traffic, shuts down after a configurable zero-player period, exposes status/control through Java 21, and can optionally be connected to ChatGPT through MCP.

## What is included

- `minecraft-bedrock`: `itzg/minecraft-bedrock-server`, persistent world/config in `./data`.
- `minecraft-wake`: Lazytainer, always present on UDP 19132 and used only as the traffic-triggered wake mechanism.
- `minecraft-control`: Java **21** + Maven + Spring Boot 4.1.0 + Spring AI 2.0.0.
- `docker-api-proxy`: a narrow HAProxy authorization boundary in front of Docker Engine.
- Optional `openai-tunnel`: OpenAI Secure MCP Tunnel client, pinned by default to `v0.0.11`.
- Host scripts for firewall, preflight, status, diagnostics and safe MCP startup.

## Behavior

1. When BDS is stopped, Lazytainer remains alive and owns the published host mapping `UDP 19132`.
2. A Minecraft connection attempt produces traffic; Lazytainer asks the **filtered** Docker API to start the existing BDS container.
3. BDS shares Lazytainer's network namespace, so it receives the same traffic path once running.
4. The Java controller never pings BDS while Docker says it is stopped, so monitoring cannot accidentally wake it.
5. While BDS runs, Java sends a RakNet Unconnected Ping and reads the Bedrock advertisement (`players/maxPlayers/version/MOTD`).
6. If players remain at zero for `MC_IDLE_TIMEOUT` (30 minutes by default), Java asks Docker to stop BDS.
7. The itzg image receives a normal `SIGTERM`, announces shutdown for 10 seconds by default, then sends Bedrock's clean `stop` command before Docker's grace period expires.
8. Authorized MCP users can add/remove Xbox/Microsoft gamertags from Bedrock's allowlist without SSH access to the host.

A failed RakNet status query never advances the idle timer. This deliberately prefers leaving the server running over stopping it because of a monitoring fault.

## Architecture and security boundary

```text
                           Linux host

Internet/LAN
   |
   | UDP 19132
   v
+----------------------+          named volume: filtered Unix socket
| minecraft-wake      |<---------------------------------------------+
| Lazytainer           |                                              |
+----------+-----------+                                              |
           | shares NETWORK namespace                                 |
           v                                                          |
+----------------------+                                              |
| minecraft-bedrock   |                                              |
| Bedrock Dedicated   |                                              |
| Server              |                                              |
+----------------------+                                              |
                                                                      |
+----------------------+      internal Docker network                 |
| minecraft-control   |------------+                                 |
| Java 21 / MCP / API |            |                                 |
+----------+-----------+            v                                 v
           |                +----------------------+       /var/run/docker.sock
           | RakNet status  | docker-api-proxy     |------------------> Docker
           +--------------->| exact HTTP allowlist |
                            +----------------------+

minecraft-control -- /mcp --> openai-tunnel (optional) -- outbound HTTPS --> OpenAI
```

### Why the wake path uses a Unix socket

`minecraft-bedrock` uses `network_mode: service:minecraft-wake`. That is required for the wake design, but it means both containers share networking. If Lazytainer were given a TCP route to a Docker API proxy, a compromised BDS process would inherit that network route too.

Instead, Lazytainer gets a **filtered Unix socket** through a named volume. Bedrock shares networking but **does not share Lazytainer's filesystem or volumes**, so it cannot see that socket. Java uses a separate internal Docker network to a different filtered frontend on the same proxy.

Only the proxy mounts the raw host Docker socket.

## Filtered Docker API

The proxy exposes two policy frontends.

**Lazytainer Unix-socket frontend** allows only:

- Docker ping/version,
- container list/inspect,
- start/stop of existing containers.

It has **no Docker exec capability**.

**Java TCP frontend**, reachable only on the internal `docker-api` network, allows:

- Docker ping/version and container list/inspect,
- start/stop of the existing `minecraft-bedrock` container,
- creation of Docker exec sessions **only in `minecraft-bedrock`**,
- start/inspect of those exec sessions.

The narrow exec capability is used by a fixed console-command bridge for allowlist changes and to read fixed allowlist/config files for verification. The bridge locates only the Bedrock process, drops to that process's UID/GID and writes to its stdin. Normal MCP input is passed as an argument and is not interpolated into shell commands.

There is still no container creation, arbitrary volume mount, image pull, network mutation or privileged-container path through the proxy. See `SECURITY.md` for the threat model and limitations.

## Existing open-source projects evaluated

| Project | Bedrock | Auto-wake | Auto-sleep | Player-aware | Java 21+ | Docker | MCP | Decision |
|---|---:|---:|---:|---:|---:|---:|---:|---|
| `timvisee/lazymc` | No | Yes | Yes | Java-protocol oriented | No (Rust) | indirect | No | Reject for Android/Bedrock |
| `joesturge/lazymc-docker-proxy` | No | Yes | Yes | Java Edition assumption | No | Yes | No | Reject for Bedrock |
| `vincss/mcsleepingserverstarter` | Yes | Yes | partial | protocol-specific | No (Node/TS) | usable | No | Useful reference, not selected |
| `vmorganp/Lazytainer` | protocol-agnostic | **Yes** | traffic-based | No | No (Go) | **Yes** | No | **Reuse only for wake** |
| `itzg/docker-minecraft-bedrock-server` | **Yes** | N/A | clean lifecycle | N/A | N/A | **Yes** | No | **Reuse for BDS** |
| This integration layer | **Yes** | delegates | **Yes** | **Yes** | **Yes** | **Yes** | **Yes** | Fills the missing gap |

The key design choice is to avoid writing a custom Bedrock UDP proxy. Lazytainer already does the protocol-agnostic traffic detection; Java handles state, player count, diagnostics, lifecycle and allowlist control.

## Requirements

- Linux host
- Docker Engine
- Docker Compose v2
- x86-64/compatible environment supported by the selected BDS image
- UDP 19132 allowed on the Linux firewall
- For players outside the LAN: router/NAT forwarding of UDP 19132 to this host
- Internet access when the BDS image needs to download/update the Bedrock server package

## Quick start

```bash
cp .env.example .env
./scripts/preflight.sh
./scripts/open-bedrock-port.sh 19132
docker compose up -d --build
```

Compose builds the Maven project through `control/Dockerfile`; there is no separate host Java/Maven installation requirement.

Follow logs:

```bash
docker compose logs -f minecraft-control minecraft-wake minecraft-bedrock
```

The Java logs report state transitions, player-count changes, idle-timer start/cancel/expiry, Docker diagnostics and allowlist mutations.
Each service uses Docker's `json-file` log driver with one file capped at 10 MB, so container logs cannot grow indefinitely.

## Automatic wake caveat

Lazytainer detects the packet that causes the wake but does not buffer/replay it into a process that did not exist yet. A cold BDS boot can therefore make the first join attempt time out. Bedrock clients normally retry discovery/connection traffic; if the UI gives up before BDS is ready, retry once after the server has started.

This is the main tradeoff of a very small wake design versus implementing a full Bedrock/RakNet proxy.

## Automatic player-aware sleep

Defaults:

```dotenv
MC_IDLE_TIMEOUT=PT30M
MC_STARTUP_GRACE=PT3M
MC_PING_TIMEOUT=PT1S
MC_MONITOR_INTERVAL_MS=30000
MC_STOP_TIMEOUT=PT45S
MC_ALLOWLIST_READY_TIMEOUT=PT90S
```

State machine:

1. Docker says BDS stopped -> no RakNet ping.
2. BDS running but inside startup grace -> no shutdown.
3. RakNet query fails -> idle timer does **not** advance.
4. Players > 0 -> idle timer resets.
5. Players = 0 -> idle timer starts/continues.
6. Zero players for `MC_IDLE_TIMEOUT` -> graceful Docker stop.

For a first test use:

```dotenv
MC_IDLE_TIMEOUT=PT2M
MC_STARTUP_GRACE=PT30S
```

## Bedrock allowlist

Allowlist enforcement defaults to enabled:

```dotenv
MC_ALLOW_LIST=true
```

For Bedrock, the simple identifier you need to ask a player for is their **Xbox/Microsoft gamertag as shown in Minecraft**. You do not need to obtain an XUID manually.

The MCP implementation applies changes through Bedrock's live `allowlist add/remove` command and verifies the persisted `allowlist.json` file afterward.

Behavior:

- `minecraft_allowlist_add`: if BDS is stopped, starts it, waits for it to answer RakNet, applies the change and verifies it. The normal idle shutdown will stop BDS later if nobody joins.
- `minecraft_allowlist_remove`: same lifecycle behavior, then verifies removal.
- `minecraft_allowlist_list`: read-only and intentionally does **not** wake a stopped server. If BDS is stopped, it reports the list as unavailable.
- Results also report whether `allow-list=true` is currently being enforced.

Example ChatGPT requests:

- “Agrega `Alex 123` al servidor de Minecraft.”
- “Quita `Alex 123` de los usuarios permitidos.”
- “¿Qué jugadores están en la allowlist?”

## Local REST API

Host mapping is intentionally loopback-only:

```bash
curl http://127.0.0.1:8080/api/status
curl http://127.0.0.1:8080/api/diagnostics
curl -X POST http://127.0.0.1:8080/api/start
curl -X POST http://127.0.0.1:8080/api/stop
curl -X POST http://127.0.0.1:8080/api/restart
```

Convenience scripts:

```bash
./scripts/status.sh
./scripts/diagnostics.sh
```

Do not publish this port directly to the Internet.

## MCP tools

Spring AI exposes Streamable HTTP MCP at `/mcp` with:

- `minecraft_status`
- `minecraft_start`
- `minecraft_stop`
- `minecraft_restart`
- `minecraft_diagnostics`
- `minecraft_allowlist_list`
- `minecraft_allowlist_add`
- `minecraft_allowlist_remove`

This makes requests such as these possible from an authorized MCP-capable ChatGPT environment:

- “¿Está prendido el servidor de Minecraft?”
- “Inicia Minecraft.”
- “¿Cuántos jugadores hay?”
- “Apaga Minecraft.”
- “Dame los diagnósticos del servidor.”
- “Agrega el gamertag `Alex 123` al servidor.”
- “Quita a `Alex 123` de la allowlist.”

### Secure MCP Tunnel setup

The recommended path is OpenAI Secure MCP Tunnel because it keeps `/mcp` private and initiates only outbound HTTPS from your network.

1. Create/associate a tunnel in the OpenAI Platform for the intended organization/workspace and obtain its tunnel ID and runtime API key.
2. Generate a local private-hop token:

```bash
openssl rand -hex 32
```

3. Put these in `.env`:

```dotenv
MCP_SHARED_TOKEN=<random value>
CONTROL_PLANE_API_KEY=<runtime API key>
CONTROL_PLANE_TUNNEL_ID=<tunnel id>
```

4. Start through the validation wrapper:

```bash
./scripts/mcp-up.sh
```

5. Inspect:

```bash
docker compose logs -f openai-tunnel minecraft-control
```

`MCP_SHARED_TOKEN` is not an OpenAI credential. It is a defense-in-depth secret for the `openai-tunnel -> minecraft-control` hop. The tunnel container injects it into both runtime and discovery requests.

Human authorization is handled through the OpenAI organization/workspace and ChatGPT developer-mode/plugin permissions. The Java service does not attempt to identify individual ChatGPT users by itself.

### MCP transport compatibility note (August 2026)

The project deliberately uses Spring AI `STREAMABLE` transport. It does not depend on stateless MCP behavior. Before relying on MCP as the only control path, smoke-test tool discovery and one read-only `minecraft_status` call in your actual ChatGPT workspace.

After adding new MCP tools, use the ChatGPT plugin's **Scan tools / Refresh tools** action if the new tool definitions are not discovered automatically.

## Diagnostics: “is port 19132 open?”

`minecraft_diagnostics` and `/api/diagnostics` can truthfully report:

- whether the Docker API is reachable,
- wake and BDS container state,
- whether Docker reports host UDP 19132 published,
- the configured public host/port,
- Bedrock responsiveness/player data through normal status calls.

They report public Internet UDP reachability as `UNKNOWN`. NAT loopback, CGNAT and router/firewall behavior cannot be proven from the same LAN host. `scripts/preflight.sh` additionally checks common host firewalls (`ufw`/`firewalld`) but still cannot replace an outside-network probe.

## Image/update policy

`.env.example` exposes image pins as variables.

- HAProxy is pinned to a concrete `3.2.22-alpine` tag by default.
- OpenAI tunnel client is pinned to `v0.0.11` by default.
- Maven build stage is pinned to `3.9.16-eclipse-temurin-21`.
- Runtime stays on the Java 21 Temurin line.
- Lazytainer defaults to the upstream-documented `master` image because that is what its own examples currently use; after a successful deployment, pin the pulled digest if you want immutable reproducibility.
- BDS uses the itzg `stable` image line plus `BDS_VERSION=LATEST` intentionally so new Bedrock Android clients do not routinely outrun the server protocol. For a controlled environment, override both to tested fixed versions/digests.

## Persistence

World/config data lives in:

```text
./data:/data
```

Do not delete `./data` when rebuilding containers. The Bedrock allowlist is persisted under the same data directory.

## Validation

The original generated project was checked for YAML/XML/Bash syntax, Java 21 compilation shape, RakNet parsing and Docker-socket isolation. The allowlist feature adds focused JUnit coverage for gamertag validation and keeps Docker exec off the Lazytainer frontend.

The repository-writing environment does not provide a Docker Engine/Maven runtime for executing the updated stack, so the final integration build should be performed on the target Linux host with the commands below. Treat the first rebuilt deployment as the integration test for the new allowlist path.

## Useful commands

```bash
# Build/start core stack
docker compose up -d --build

# State
docker compose ps
./scripts/status.sh

# Logs
docker compose logs -f minecraft-control minecraft-wake minecraft-bedrock

# Rebuild Java only
docker compose build --pull minecraft-control
docker compose up -d minecraft-control

# Stop the entire infrastructure (different from letting BDS sleep)
docker compose down

# Start/update optional MCP tunnel
./scripts/mcp-up.sh
```

## Recommended next hardening after first successful run

1. Keep the Bedrock allowlist limited to actual players.
2. Back up `data/` automatically.
3. Pin tested container digests.
4. Run your preferred container/SCA vulnerability scanner on the resolved images and Maven dependency tree.
5. Test MCP with a read-only status call before granting plugin access to other users.
6. Verify UDP 19132 from a phone on cellular data or another truly external network.
