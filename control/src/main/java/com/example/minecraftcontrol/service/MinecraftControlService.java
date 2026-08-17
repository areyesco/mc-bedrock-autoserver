package com.example.minecraftcontrol.service;

import com.example.minecraftcontrol.bedrock.BedrockStatus;
import com.example.minecraftcontrol.bedrock.BedrockStatusClient;
import com.example.minecraftcontrol.config.MinecraftProperties;
import com.example.minecraftcontrol.docker.ContainerState;
import com.example.minecraftcontrol.docker.DockerApiClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;

@Service
public class MinecraftControlService {
    private static final Logger log = LoggerFactory.getLogger(MinecraftControlService.class);
    private static final Duration WAKE_RESTART_TIMEOUT = Duration.ofSeconds(10);
    private final MinecraftProperties properties;
    private final DockerApiClient docker;
    private final BedrockStatusClient bedrock;
    private final BedrockAllowlistManager allowlistManager;
    private Instant emptySince;
    private Boolean lastRunning;
    private Integer lastPlayers;

    public MinecraftControlService(MinecraftProperties properties, DockerApiClient docker, BedrockStatusClient bedrock,
                                   BedrockAllowlistManager allowlistManager) {
        this.properties = properties;
        this.docker = docker;
        this.bedrock = bedrock;
        this.allowlistManager = allowlistManager;
    }

    @EventListener(ApplicationReadyEvent.class)
    public synchronized void onReady() {
        log.info("minecraft-control ready: Java={}, idleTimeout={}, startupGrace={}, monitor={}ms",
                Runtime.version(), properties.idleTimeout(), properties.startupGrace(), properties.monitorIntervalMs());
        try {
            Diagnostics d = diagnostics();
            log.info("startup diagnostics: dockerApiReachable={}, minecraftState={}, wakeState={}, udpPublished={}, externalReachability={}",
                    d.dockerApiReachable(), d.minecraftContainerState(), d.wakeContainerState(), d.udp19132PublishedByDocker(), d.externalReachability());
            if (!d.udp19132PublishedByDocker()) log.warn("UDP {} is not reported as published by Docker.", properties.publicPort());
            log.info("External UDP reachability cannot be proven from inside this host; router/NAT/firewall needs an outside vantage point.");
        } catch (Exception e) {
            log.error("Startup diagnostics failed: {}", e.getMessage(), e);
        }
    }

    public synchronized ServerStatus status() {
        ContainerState container = docker.inspect(properties.containerName());
        if (!container.running()) {
            return new ServerStatus(container.status(), false, false, null, null, null, null, container.startedAt(), emptySince, null);
        }
        return toStatus(container, bedrock.query());
    }

    public synchronized Diagnostics diagnostics() {
        boolean dockerOk = docker.ping();
        ContainerState minecraft = docker.inspect(properties.containerName());
        ContainerState wake = docker.inspect(properties.lazytainerContainerName());
        boolean published = wake.portBindings().stream().anyMatch(binding ->
                "19132/udp".equals(binding.containerPort()) && Integer.toString(properties.publicPort()).equals(binding.hostPort()));
        String publicHost = properties.publicHost() == null ? "" : properties.publicHost().trim();
        return new Diagnostics(dockerOk, minecraft.status(), wake.status(), published, wake.portBindings(), publicHost,
                properties.publicPort(), "UNKNOWN",
                "Local inspection verifies Docker's UDP publish only. Router/NAT/firewall reachability needs a probe from outside the LAN.");
    }

    public synchronized ControlResult start(String reason) {
        ContainerState before = docker.inspect(properties.containerName());
        if (before.running()) return new ControlResult(true, false, "start", "Minecraft is already running.", safeStatus());
        var result = docker.start(properties.containerName());
        emptySince = null; lastPlayers = null;
        log.info("Minecraft START requested. reason={}", sanitizeReason(reason));
        return new ControlResult(true, result.changed(), "start", "Minecraft start requested. Bedrock can take a while to become ready.", safeStatus());
    }

    public synchronized ControlResult stop(String reason) {
        ContainerState before = docker.inspect(properties.containerName());
        if (!before.running()) {
            emptySince = null;
            return new ControlResult(true, false, "stop", "Minecraft is already stopped.", safeStatus());
        }
        var result = docker.stop(properties.containerName(), properties.stopTimeout());
        resetWakePacketHistory();
        emptySince = null; lastPlayers = null;
        log.info("Minecraft STOP requested. reason={}", sanitizeReason(reason));
        return new ControlResult(true, result.changed(), "stop", "Minecraft stop requested with a graceful timeout.", safeStatus());
    }

    public synchronized ControlResult restart(String reason) {
        ContainerState before = docker.inspect(properties.containerName());
        if (before.running()) docker.stop(properties.containerName(), properties.stopTimeout());
        docker.start(properties.containerName());
        emptySince = null; lastPlayers = null;
        log.info("Minecraft RESTART requested. reason={}", sanitizeReason(reason));
        return new ControlResult(true, true, "restart", "Minecraft restart requested.", safeStatus());
    }

    public synchronized AllowlistStatus allowlist() {
        return allowlistManager.list();
    }

    public synchronized AllowlistMutationResult addAllowlistPlayer(String gamertag) {
        // Keep the idle monitor out of this critical section and reset any stale idle countdown.
        emptySince = null;
        lastPlayers = null;
        return allowlistManager.add(gamertag);
    }

    public synchronized AllowlistMutationResult removeAllowlistPlayer(String gamertag) {
        emptySince = null;
        lastPlayers = null;
        return allowlistManager.remove(gamertag);
    }

    @Scheduled(fixedDelayString = "${minecraft.monitor-interval-ms:30000}")
    public synchronized void monitorIdlePlayers() {
        try {
            ContainerState container = docker.inspect(properties.containerName());
            logRunningTransition(container);
            if (!container.running()) { emptySince = null; lastPlayers = null; return; }

            if (container.startedAt() != null) {
                Duration uptime = Duration.between(container.startedAt(), Instant.now());
                if (!uptime.isNegative() && uptime.compareTo(properties.startupGrace()) < 0) return;
            }

            BedrockStatus bds = bedrock.query();
            if (!bds.responding()) {
                log.warn("Bedrock status query failed while container runs; idle timer will not advance. error={}", bds.error());
                return;
            }
            int players = bds.players();
            if (!Objects.equals(lastPlayers, players)) {
                log.info("Player count changed: {} -> {}", lastPlayers == null ? "unknown" : lastPlayers, players);
                lastPlayers = players;
            }
            if (players > 0) {
                if (emptySince != null) log.info("Idle shutdown cancelled: {} player(s) connected.", players);
                emptySince = null;
                return;
            }
            Instant now = Instant.now();
            if (emptySince == null) {
                emptySince = now;
                log.info("No players connected. Idle timer started; shutdown in {}.", properties.idleTimeout());
                return;
            }
            Duration idle = Duration.between(emptySince, now);
            if (idle.compareTo(properties.idleTimeout()) >= 0) {
                log.info("Idle timeout reached after {} with zero players. Stopping Minecraft gracefully.", idle);
                docker.stop(properties.containerName(), properties.stopTimeout());
                resetWakePacketHistory();
                emptySince = null; lastPlayers = null;
            }
        } catch (Exception e) {
            log.error("Idle monitor failed; no shutdown action taken. {}", e.getMessage(), e);
        }
    }

    private void logRunningTransition(ContainerState container) {
        if (lastRunning == null || lastRunning != container.running()) {
            log.info("Minecraft container state transition: running={}, status={}", container.running(), container.status());
            lastRunning = container.running();
        }
    }

    /**
     * Lazytainer keeps a rolling packet history while Bedrock is running. The final
     * status probes used to decide an idle shutdown remain in that history and would
     * otherwise satisfy its wake threshold as soon as Bedrock stops. Restarting only
     * the always-on wake container clears that in-memory history while preserving its
     * network configuration, published UDP port and the stopped Bedrock container.
     */
    private void resetWakePacketHistory() {
        try {
            docker.restart(properties.lazytainerContainerName(), WAKE_RESTART_TIMEOUT);
            log.info("Lazytainer restarted after Minecraft stop to clear pre-stop packet history.");
        } catch (Exception e) {
            log.error("Minecraft stopped, but Lazytainer packet history could not be reset; an immediate false wake is possible. {}",
                    e.getMessage(), e);
        }
    }

    private ServerStatus safeStatus() {
        try { return status(); }
        catch (Exception e) { return new ServerStatus("unknown", false, false, null, null, null, null, null, emptySince, null); }
    }

    private ServerStatus toStatus(ContainerState container, BedrockStatus bds) {
        Long remaining = null;
        if (emptySince != null) {
            long total = properties.idleTimeout().toSeconds();
            long elapsed = Math.max(0, Duration.between(emptySince, Instant.now()).toSeconds());
            remaining = Math.max(0, total - elapsed);
        }
        return new ServerStatus(container.status(), true, bds.responding(), bds.responding() ? bds.players() : null,
                bds.responding() ? bds.maxPlayers() : null, bds.responding() ? bds.version() : null,
                bds.responding() ? bds.motd() : null, container.startedAt(), emptySince, remaining);
    }

    private static String sanitizeReason(String reason) {
        if (reason == null || reason.isBlank()) return "unspecified";
        return reason.replaceAll("[\\r\\n\\t]", " ").trim();
    }
}
