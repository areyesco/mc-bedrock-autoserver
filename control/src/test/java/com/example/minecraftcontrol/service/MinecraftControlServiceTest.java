package com.example.minecraftcontrol.service;

import com.example.minecraftcontrol.bedrock.BedrockStatus;
import com.example.minecraftcontrol.bedrock.BedrockStatusClient;
import com.example.minecraftcontrol.config.MinecraftProperties;
import com.example.minecraftcontrol.docker.ContainerState;
import com.example.minecraftcontrol.docker.DockerApiClient;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.mockito.Mockito.*;

class MinecraftControlServiceTest {

    @Test
    void manualStopClearsLazytainerPacketHistory() {
        DockerApiClient docker = mock(DockerApiClient.class);
        BedrockStatusClient bedrock = mock(BedrockStatusClient.class);
        BedrockAllowlistManager allowlist = mock(BedrockAllowlistManager.class);
        MinecraftProperties properties = properties(Duration.ofMinutes(30));
        ContainerState running = state(true, Instant.now().minus(Duration.ofHours(1)));
        ContainerState stopped = state(false, null);

        when(docker.inspect("minecraft-bedrock")).thenReturn(running, stopped);
        when(docker.stop("minecraft-bedrock", Duration.ofSeconds(45)))
                .thenReturn(new DockerApiClient.MutationResult(true, "stopped"));
        when(docker.restart("minecraft-wake", Duration.ofSeconds(10)))
                .thenReturn(new DockerApiClient.MutationResult(true, "restarted"));

        MinecraftControlService service = new MinecraftControlService(properties, docker, bedrock, allowlist);
        service.stop("test");

        verify(docker).stop("minecraft-bedrock", Duration.ofSeconds(45));
        verify(docker).restart("minecraft-wake", Duration.ofSeconds(10));
    }

    @Test
    void idleStopClearsLazytainerPacketHistory() {
        DockerApiClient docker = mock(DockerApiClient.class);
        BedrockStatusClient bedrock = mock(BedrockStatusClient.class);
        BedrockAllowlistManager allowlist = mock(BedrockAllowlistManager.class);
        MinecraftProperties properties = properties(Duration.ZERO);
        ContainerState running = state(true, Instant.now().minus(Duration.ofHours(1)));

        when(docker.inspect("minecraft-bedrock")).thenReturn(running);
        when(bedrock.query()).thenReturn(new BedrockStatus(true, "test", 0, "test", 0, 10, "Survival", null));
        when(docker.stop("minecraft-bedrock", Duration.ofSeconds(45)))
                .thenReturn(new DockerApiClient.MutationResult(true, "stopped"));
        when(docker.restart("minecraft-wake", Duration.ofSeconds(10)))
                .thenReturn(new DockerApiClient.MutationResult(true, "restarted"));

        MinecraftControlService service = new MinecraftControlService(properties, docker, bedrock, allowlist);
        service.monitorIdlePlayers();
        service.monitorIdlePlayers();

        verify(docker).stop("minecraft-bedrock", Duration.ofSeconds(45));
        verify(docker).restart("minecraft-wake", Duration.ofSeconds(10));
    }

    private static MinecraftProperties properties(Duration idleTimeout) {
        return new MinecraftProperties(
                "http://docker-api-proxy:2375", "minecraft-bedrock", "minecraft-wake",
                "minecraft-wake", 19132, idleTimeout, Duration.ZERO, Duration.ofSeconds(1),
                Duration.ofSeconds(45), Duration.ofSeconds(90), 30_000,
                "", 19132, "test-token");
    }

    private static ContainerState state(boolean running, Instant startedAt) {
        return new ContainerState("minecraft-bedrock", running, running ? "running" : "exited", startedAt, List.of());
    }
}
