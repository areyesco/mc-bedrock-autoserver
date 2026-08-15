# OSS evaluation and design decision

**Review date:** 2026-08-15

## Requirements used for evaluation

- Minecraft Bedrock / Android clients.
- Linux + Docker Compose.
- Wake automatically when connection traffic reaches UDP 19132.
- Stop automatically after a real zero-player interval (default 30 minutes).
- Java 21 or newer for the custom integration layer.
- Maven build inside Docker Compose.
- Useful logs and diagnostics.
- Optional MCP control for status/start/stop/restart/diagnostics.
- Avoid exposing a broad Docker Engine API or raw Docker socket to the Java application.

## Projects reviewed

### timvisee/lazymc

**Strengths:** mature sleep/wake proxy concept, efficient, graceful lifecycle features.

**Mismatch:** it is explicitly a Minecraft Java Edition proxy and is implemented in Rust. It is not the correct network protocol path for a native Android Bedrock client.

**Decision:** do not use.

### joesturge/lazymc-docker-proxy

**Strengths:** convenient Docker-oriented packaging around LazyMC.

**Mismatch:** still inherits LazyMC's Java Edition protocol assumptions.

**Decision:** do not use for this Bedrock deployment.

### vincss/mcsleepingserverstarter

**Strengths:** understands both Java and Bedrock wake scenarios, has Docker support and a web UI.

**Mismatch:** Node/TypeScript rather than Java 21; its current README advertises Bedrock compatibility against a specific older Bedrock client line rather than being protocol-agnostic. Its own docs recommend separate player-empty shutdown helpers for the stop side.

**Decision:** useful reference implementation, but not selected for the production path.

### vmorganp/Lazytainer

**Strengths:** small, MIT-licensed, protocol-agnostic traffic-based wake/sleep, Docker-native grouping, supports `network_mode: service:lazytainer`, configurable ports/threshold/polling/stop behavior. Latest GitHub release observed during review: `v2.0.30`.

**Weaknesses:** upstream examples normally mount the raw Docker socket, and the documented Compose image uses a moving `master` tag. Traffic silence is not the same as a trustworthy Minecraft player count.

**Decision:** reuse **only the traffic-triggered wake behavior**. Do not let its traffic-based idle timeout decide normal Minecraft shutdown. Put a filtered Docker API boundary in front of it and use a filesystem-only filtered Unix socket rather than the raw host socket.

### itzg/docker-minecraft-bedrock-server

**Strengths:** purpose-built Bedrock server container, persistent `/data`, configurable server properties, `VERSION=LATEST`, and graceful stop/announcement behavior. Latest GitHub release observed during review: `2026.8.1`.

**Decision:** use as the BDS runtime.

### OpenAI tunnel-client

**Strengths:** official Secure MCP Tunnel client, outbound-only private MCP connectivity, health/readiness/metrics surfaces, static header injection, organization/workspace tunnel controls. Latest stable GitHub release observed during review: `v0.0.11`.

**Decision:** optional Compose profile for ChatGPT MCP connectivity. Pin default to `v0.0.11` rather than a moving `latest` tag.

### Spring AI MCP server

**Strengths:** Java-native MCP server support with Spring Boot, annotation-based tools, Streamable HTTP, Java 21-compatible stack.

**Decision:** use Spring AI `2.0.0` with `STREAMABLE` transport for the Java control application.

## Final architecture decision

Use existing open source for the two things it already does well:

1. **itzg BDS image** for Bedrock itself.
2. **Lazytainer** only for traffic-triggered wake.

Write the missing integration layer in Java 21:

- Docker state/control through a filtered API.
- RakNet Bedrock status/player-count query.
- player-aware 30-minute shutdown logic.
- startup diagnostics and state-change logging.
- localhost REST control.
- MCP tools.

Add OpenAI's official tunnel client only when MCP is enabled.

## Security review conclusions

### Raw Docker socket

The raw Docker socket is a host-administration capability. This project mounts it only into one narrow HAProxy boundary container. Java never sees it.

Lazytainer also does not see it. Instead, HAProxy creates a **filtered Unix socket** in a named volume and Lazytainer gets that volume. This is intentionally different from the common upstream Lazytainer example.

The Bedrock container shares Lazytainer's **network namespace** but not its **filesystem volumes**, so it does not inherit the filtered Docker Unix socket. Bedrock is also not attached to the internal TCP Docker API network used by Java.

### Docker API allowlist

The HAProxy policy permits only:

- Docker ping/version,
- container list,
- container inspect,
- container start,
- container stop.

It denies create/exec/images/volumes/networks/privileged-container operations.

This is materially safer than exposing a generic Docker socket proxy, but it is still a privileged host boundary. Treat changes to its configuration as administrator-level changes.

### MCP

MCP is not host-published. The recommended OpenAI Secure MCP Tunnel creates an outbound HTTPS path instead of an inbound Internet listener.

`MCP_SHARED_TOKEN` protects the final tunnel-client -> Java hop. OpenAI organization/workspace permissions should control which human accounts are allowed to discover/use the tunnel. The Java application does not attempt to perform human identity federation itself.

### External UDP diagnostics

A LAN host cannot reliably prove that a public UDP port is traversable through ISP/CGNAT/router/firewall from the Internet. The implementation deliberately reports this as `UNKNOWN` rather than fabricating certainty. The host preflight script checks common local firewalls and Docker's local publish; an outside-network test is still required.

## Remaining risks / things to verify on the target host

1. Build and run the full Compose stack with the actual Docker Engine; the generation environment did not contain Docker/Maven.
2. Confirm Lazytainer can use `DOCKER_HOST=unix:///run/docker-api/docker.sock` against the filtered socket on the target Docker version.
3. Confirm the first Bedrock connection attempt retries long enough for a cold BDS boot; otherwise the player may need one manual reconnect after wake.
4. Test OpenAI MCP tunnel discovery and `minecraft_status` in the actual intended ChatGPT workspace before enabling mutation tools for family users.
5. Run an SCA/container vulnerability scanner against the resolved Maven tree and pulled container image digests. This review is not a penetration test or exhaustive CVE audit.
6. Configure a Bedrock player allowlist before exposing UDP 19132 broadly to the Internet.
