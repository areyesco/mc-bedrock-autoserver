package com.example.minecraftcontrol.mcp;

import com.example.minecraftcontrol.service.*;
import org.springframework.ai.mcp.annotation.McpTool;
import org.springframework.ai.mcp.annotation.McpToolParam;
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

    @McpTool(name = "minecraft_allowlist_list", description = "Read the Minecraft Bedrock allowlist and whether allow-list enforcement is enabled. This read-only operation does not wake a stopped server; when Minecraft is stopped the list is reported as unavailable.")
    public AllowlistStatus allowlist() { return service.allowlist(); }

    @McpTool(name = "minecraft_allowlist_add", description = "Add a player to the Minecraft Bedrock allowlist using only the Xbox/Microsoft gamertag shown in Minecraft. No XUID is required. If Minecraft is stopped, it is started so Bedrock can apply and verify the change; normal idle shutdown remains in effect afterward.")
    public AllowlistMutationResult allowlistAdd(
            @McpToolParam(description = "Xbox/Microsoft gamertag exactly as shown in Minecraft Bedrock, for example Alex123 or Alex 123.", required = true)
            String gamertag) {
        return service.addAllowlistPlayer(gamertag);
    }

    @McpTool(name = "minecraft_allowlist_remove", description = "Remove a player from the Minecraft Bedrock allowlist using the Xbox/Microsoft gamertag shown in Minecraft. If Minecraft is stopped, it is started so Bedrock can apply and verify the change; normal idle shutdown remains in effect afterward.")
    public AllowlistMutationResult allowlistRemove(
            @McpToolParam(description = "Xbox/Microsoft gamertag exactly as shown in Minecraft Bedrock.", required = true)
            String gamertag) {
        return service.removeAllowlistPlayer(gamertag);
    }
}
