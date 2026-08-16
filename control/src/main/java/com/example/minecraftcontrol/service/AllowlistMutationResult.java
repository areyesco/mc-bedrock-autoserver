package com.example.minecraftcontrol.service;

public record AllowlistMutationResult(
        boolean success,
        boolean changed,
        String action,
        String gamertag,
        boolean serverStartedForOperation,
        String message,
        AllowlistStatus allowlist
) {}
