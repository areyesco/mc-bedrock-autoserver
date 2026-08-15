package com.example.minecraftcontrol.service;

import java.time.Instant;

public record ServerStatus(
        String containerState,
        boolean running,
        boolean bedrockResponding,
        Integer players,
        Integer maxPlayers,
        String bedrockVersion,
        String motd,
        Instant startedAt,
        Instant emptySince,
        Long idleSecondsRemaining
) {}
