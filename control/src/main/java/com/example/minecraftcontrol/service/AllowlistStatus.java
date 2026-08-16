package com.example.minecraftcontrol.service;

import java.util.List;

public record AllowlistStatus(
        boolean minecraftRunning,
        Boolean allowlistEnabled,
        boolean dataAvailable,
        List<AllowlistEntry> players,
        String message
) {}
