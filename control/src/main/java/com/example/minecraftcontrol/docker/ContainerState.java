package com.example.minecraftcontrol.docker;

import java.time.Instant;
import java.util.List;

public record ContainerState(
        String name,
        boolean running,
        String status,
        Instant startedAt,
        List<PortBinding> portBindings
) {
    public record PortBinding(String containerPort, String hostIp, String hostPort) {}
}
