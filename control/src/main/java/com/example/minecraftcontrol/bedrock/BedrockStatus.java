package com.example.minecraftcontrol.bedrock;

public record BedrockStatus(
        boolean responding,
        String motd,
        int protocol,
        String version,
        int players,
        int maxPlayers,
        String gameMode,
        String error
) {
    public static BedrockStatus unavailable(String error) {
        return new BedrockStatus(false, null, -1, null, -1, -1, null, error);
    }
}
