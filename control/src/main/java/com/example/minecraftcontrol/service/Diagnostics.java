package com.example.minecraftcontrol.service;

import com.example.minecraftcontrol.docker.ContainerState;
import java.util.List;

public record Diagnostics(
        boolean dockerApiReachable,
        String minecraftContainerState,
        String wakeContainerState,
        boolean udp19132PublishedByDocker,
        List<ContainerState.PortBinding> wakePortBindings,
        String configuredPublicHost,
        int configuredPublicPort,
        String externalReachability,
        String externalReachabilityReason
) {}
