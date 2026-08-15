package com.example.minecraftcontrol.service;

public record ControlResult(
        boolean success,
        boolean changed,
        String action,
        String message,
        ServerStatus status
) {}
