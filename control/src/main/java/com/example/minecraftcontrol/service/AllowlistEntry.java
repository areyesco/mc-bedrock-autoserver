package com.example.minecraftcontrol.service;

public record AllowlistEntry(
        String name,
        String xuid,
        boolean ignoresPlayerLimit
) {}
