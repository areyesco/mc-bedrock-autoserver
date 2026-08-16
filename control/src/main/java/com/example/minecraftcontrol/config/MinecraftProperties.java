package com.example.minecraftcontrol.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import java.time.Duration;

@ConfigurationProperties(prefix = "minecraft")
public record MinecraftProperties(
        String dockerApiBaseUrl,
        String containerName,
        String lazytainerContainerName,
        String bedrockHost,
        int bedrockPort,
        Duration idleTimeout,
        Duration startupGrace,
        Duration pingTimeout,
        Duration stopTimeout,
        Duration allowlistReadyTimeout,
        long monitorIntervalMs,
        String publicHost,
        int publicPort,
        String mcpSharedToken
) {}
