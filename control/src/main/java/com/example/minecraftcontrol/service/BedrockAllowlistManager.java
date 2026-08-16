package com.example.minecraftcontrol.service;

import com.example.minecraftcontrol.bedrock.BedrockStatus;
import com.example.minecraftcontrol.bedrock.BedrockStatusClient;
import com.example.minecraftcontrol.config.MinecraftProperties;
import com.example.minecraftcontrol.docker.ContainerState;
import com.example.minecraftcontrol.docker.DockerApiClient;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@Component
public class BedrockAllowlistManager {
    private static final Logger log = LoggerFactory.getLogger(BedrockAllowlistManager.class);
    private static final Duration EXEC_TIMEOUT = Duration.ofSeconds(10);
    private static final Duration VERIFY_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration VERIFY_POLL = Duration.ofMillis(250);

    private final MinecraftProperties properties;
    private final DockerApiClient docker;
    private final BedrockStatusClient bedrock;

    public BedrockAllowlistManager(MinecraftProperties properties, DockerApiClient docker, BedrockStatusClient bedrock) {
        this.properties = properties;
        this.docker = docker;
        this.bedrock = bedrock;
    }

    public AllowlistStatus list() {
        ContainerState state = docker.inspect(properties.containerName());
        if (!state.running()) {
            return new AllowlistStatus(false, null, false, List.of(),
                    "Minecraft is stopped. The allowlist is not read through Docker exec while the container is stopped.");
        }
        return readStatus();
    }

    public AllowlistMutationResult add(String requestedGamertag) {
        String gamertag = normalizeGamertag(requestedGamertag);
        boolean started = ensureServerReady();
        AllowlistStatus before = readStatus();
        if (contains(before.players(), gamertag)) {
            return new AllowlistMutationResult(true, false, "add", gamertag, started,
                    enabledMessage(before.allowlistEnabled(), "Player is already present in the Bedrock allowlist."), before);
        }

        runAllowlistCommand("add", gamertag);
        AllowlistStatus after = waitForPresence(gamertag, true);
        if (!contains(after.players(), gamertag)) {
            throw new IllegalStateException("Bedrock accepted the allowlist command process, but the player did not appear in allowlist.json: " + gamertag);
        }
        log.info("Allowlist ADD applied for gamertag={}", sanitizeForLog(gamertag));
        return new AllowlistMutationResult(true, true, "add", gamertag, started,
                enabledMessage(after.allowlistEnabled(), "Player added to the Bedrock allowlist."), after);
    }

    public AllowlistMutationResult remove(String requestedGamertag) {
        String gamertag = normalizeGamertag(requestedGamertag);
        boolean started = ensureServerReady();
        AllowlistStatus before = readStatus();
        if (!contains(before.players(), gamertag)) {
            return new AllowlistMutationResult(true, false, "remove", gamertag, started,
                    enabledMessage(before.allowlistEnabled(), "Player was not present in the Bedrock allowlist."), before);
        }

        runAllowlistCommand("remove", gamertag);
        AllowlistStatus after = waitForPresence(gamertag, false);
        if (contains(after.players(), gamertag)) {
            throw new IllegalStateException("Bedrock accepted the allowlist command process, but the player is still present in allowlist.json: " + gamertag);
        }
        log.info("Allowlist REMOVE applied for gamertag={}", sanitizeForLog(gamertag));
        return new AllowlistMutationResult(true, true, "remove", gamertag, started,
                enabledMessage(after.allowlistEnabled(), "Player removed from the Bedrock allowlist."), after);
    }

    private boolean ensureServerReady() {
        ContainerState initial = docker.inspect(properties.containerName());
        boolean started = false;
        if (!initial.running()) {
            DockerApiClient.MutationResult result = docker.start(properties.containerName());
            started = result.changed();
            log.info("Minecraft started to apply allowlist change.");
        }

        Duration readyTimeout = properties.allowlistReadyTimeout();
        if (readyTimeout == null || readyTimeout.isZero() || readyTimeout.isNegative()) readyTimeout = Duration.ofSeconds(90);
        Instant deadline = Instant.now().plus(readyTimeout);
        String lastError = null;

        while (Instant.now().isBefore(deadline)) {
            ContainerState current = docker.inspect(properties.containerName());
            if (current.running()) {
                BedrockStatus status = bedrock.query();
                if (status.responding()) return started;
                lastError = status.error();
            } else {
                lastError = "container state=" + current.status();
            }
            sleep(Duration.ofMillis(750));
        }

        throw new IllegalStateException("Minecraft did not become ready for an allowlist command within " + readyTimeout
                + (lastError == null || lastError.isBlank() ? "" : ". Last status error: " + lastError));
    }

    private void runAllowlistCommand(String action, String gamertag) {
        String quotedGamertag = "\"" + gamertag + "\"";
        DockerApiClient.ExecResult result = docker.exec(properties.containerName(),
                List.of("send-command", "allowlist", action, quotedGamertag), EXEC_TIMEOUT);
        if (result.exitCode() != 0) {
            String output = result.output() == null ? "" : result.output().trim();
            if (output.length() > 300) output = output.substring(0, 300) + "...";
            throw new IllegalStateException("send-command allowlist " + action + " failed with exit code "
                    + result.exitCode() + (output.isBlank() ? "" : ": " + output));
        }
    }

    private AllowlistStatus waitForPresence(String gamertag, boolean expectedPresent) {
        Instant deadline = Instant.now().plus(VERIFY_TIMEOUT);
        AllowlistStatus last = readStatus();
        while (contains(last.players(), gamertag) != expectedPresent && Instant.now().isBefore(deadline)) {
            sleep(VERIFY_POLL);
            last = readStatus();
        }
        return last;
    }

    private AllowlistStatus readStatus() {
        DockerApiClient.ExecResult propertyResult = docker.exec(properties.containerName(), List.of("sh", "-c",
                "sed -n 's/^allow-list=//p' /data/server.properties | tail -n 1"), EXEC_TIMEOUT);
        if (propertyResult.exitCode() != 0) {
            throw new IllegalStateException("Unable to read allow-list from server.properties; exit code=" + propertyResult.exitCode());
        }
        String property = propertyResult.output() == null ? "" : propertyResult.output().trim();
        Boolean enabled = property.equalsIgnoreCase("true") ? Boolean.TRUE
                : property.equalsIgnoreCase("false") ? Boolean.FALSE : null;

        DockerApiClient.ExecResult fileResult = docker.exec(properties.containerName(), List.of("sh", "-c",
                "if [ -r /data/allowlist.json ]; then cat /data/allowlist.json; else printf '[]'; fi"), EXEC_TIMEOUT);
        if (fileResult.exitCode() != 0) {
            throw new IllegalStateException("Unable to read /data/allowlist.json; exit code=" + fileResult.exitCode());
        }

        String json = fileResult.output() == null || fileResult.output().isBlank() ? "[]" : fileResult.output().trim();
        JsonElement parsed = JsonParser.parseString(json);
        if (!parsed.isJsonArray()) throw new IllegalStateException("/data/allowlist.json is not a JSON array");

        List<AllowlistEntry> players = new ArrayList<>();
        JsonArray array = parsed.getAsJsonArray();
        for (JsonElement element : array) {
            if (!element.isJsonObject()) continue;
            JsonObject object = element.getAsJsonObject();
            String name = getString(object, "name");
            if (name == null || name.isBlank()) continue;
            String xuid = getString(object, "xuid");
            boolean ignoresPlayerLimit = object.has("ignoresPlayerLimit") && !object.get("ignoresPlayerLimit").isJsonNull()
                    && object.get("ignoresPlayerLimit").getAsBoolean();
            players.add(new AllowlistEntry(name, xuid, ignoresPlayerLimit));
        }

        return new AllowlistStatus(true, enabled, true, List.copyOf(players),
                enabled == null ? "Allowlist data read; enforcement state could not be determined."
                        : enabled ? "Bedrock allowlist enforcement is enabled." : "Bedrock allowlist enforcement is disabled.");
    }

    static String normalizeGamertag(String value) {
        if (value == null) throw new IllegalArgumentException("Xbox/Microsoft gamertag is required");
        String gamertag = value.trim();
        if (gamertag.isEmpty()) throw new IllegalArgumentException("Xbox/Microsoft gamertag is required");
        if (gamertag.length() > 64) throw new IllegalArgumentException("Gamertag is too long");
        for (int i = 0; i < gamertag.length(); i++) {
            char ch = gamertag.charAt(i);
            if (Character.isISOControl(ch)) throw new IllegalArgumentException("Gamertag contains control characters");
        }
        if (gamertag.indexOf('"') >= 0 || gamertag.indexOf('\\') >= 0) {
            throw new IllegalArgumentException("Gamertag cannot contain quote or backslash characters");
        }
        return gamertag;
    }

    private static boolean contains(List<AllowlistEntry> entries, String gamertag) {
        return entries.stream().anyMatch(entry -> entry.name().equalsIgnoreCase(gamertag));
    }

    private static String enabledMessage(Boolean enabled, String base) {
        if (Boolean.FALSE.equals(enabled)) {
            return base + " Warning: allow-list=false, so Bedrock is not currently enforcing the allowlist.";
        }
        if (enabled == null) {
            return base + " Warning: the current allow-list enforcement state could not be determined.";
        }
        return base;
    }

    private static String getString(JsonObject object, String property) {
        JsonElement value = object.get(property);
        return value == null || value.isJsonNull() ? null : value.getAsString();
    }

    private static String sanitizeForLog(String value) {
        return value.replaceAll("[\\r\\n\\t]", " ");
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(Math.max(1, duration.toMillis()));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Interrupted while waiting for Bedrock allowlist operation", e);
        }
    }
}
