package com.example.minecraftcontrol.mcp;

import com.example.minecraftcontrol.service.*;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.stereotype.Component;

@Component
public class MinecraftMcpTools {
    private final MinecraftControlService service;
    public MinecraftMcpTools(MinecraftControlService service) { this.service = service; }

    @McpTool(name = "minecraft_status", description = "Read the Minecraft Bedrock server status, including container state and live player count when Bedrock responds.")
    public ServerStatus status() { return service.status(); }

    @McpTool(name = "minecraft_start", description = "Start the configured existing Minecraft Bedrock container if it is stopped. Safe and idempotent.")
    public ControlResult start() { return service.start("MCP minecraft_start"); }

    @McpTool(name = "minecraft_stop", description = "Gracefully stop the configured Minecraft Bedrock container. Safe and idempotent.")
    public ControlResult stop() { return service.stop("MCP minecraft_stop"); }

    @McpTool(name = "minecraft_restart", description = "Gracefully restart the configured Minecraft Bedrock container.")
    public ControlResult restart() { return service.restart("MCP minecraft_restart"); }

    @McpTool(name = "minecraft_diagnostics", description = "Read Docker, UDP publish and network diagnostics. Internet router/NAT reachability is reported as UNKNOWN without an outside probe.")
    public Diagnostics diagnostics() { return service.diagnostics(); }
}
