package com.example.minecraftcontrol.docker;

import com.example.minecraftcontrol.config.MinecraftProperties;
import com.google.gson.*;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

@Component
public class DockerApiClient {
    private final HttpClient httpClient;
    private final String baseUrl;
    private volatile String apiPrefix;

    public DockerApiClient(MinecraftProperties properties) {
        this.baseUrl = stripTrailingSlash(properties.dockerApiBaseUrl());
        this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();
    }

    public boolean ping() {
        try {
            var response = send("GET", "/_ping", Duration.ofSeconds(3), null);
            return response.statusCode() == 200 && response.body().trim().equalsIgnoreCase("OK");
        } catch (Exception e) {
            return false;
        }
    }

    public ContainerState inspect(String containerName) {
        String path = versioned("/containers/" + encodePathSegment(containerName) + "/json");
        var response = sendUnchecked("GET", path, Duration.ofSeconds(5));
        if (response.statusCode() == 404) throw new IllegalStateException("Docker container not found: " + containerName);
        require2xx(response, "inspect " + containerName);

        JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
        JsonObject state = root.getAsJsonObject("State");
        boolean running = state != null && getBoolean(state, "Running", false);
        String status = state == null ? "unknown" : getString(state, "Status", "unknown");
        Instant startedAt = state == null ? null : parseInstant(getString(state, "StartedAt", null));

        List<ContainerState.PortBinding> bindings = new ArrayList<>();
        JsonObject networkSettings = root.getAsJsonObject("NetworkSettings");
        if (networkSettings != null && networkSettings.has("Ports") && networkSettings.get("Ports").isJsonObject()) {
            JsonObject ports = networkSettings.getAsJsonObject("Ports");
            for (var entry : ports.entrySet()) {
                if (entry.getValue() == null || entry.getValue().isJsonNull() || !entry.getValue().isJsonArray()) continue;
                for (JsonElement element : entry.getValue().getAsJsonArray()) {
                    if (!element.isJsonObject()) continue;
                    JsonObject binding = element.getAsJsonObject();
                    bindings.add(new ContainerState.PortBinding(entry.getKey(),
                            getString(binding, "HostIp", ""), getString(binding, "HostPort", "")));
                }
            }
        }

        String actualName = getString(root, "Name", containerName);
        if (actualName.startsWith("/")) actualName = actualName.substring(1);
        return new ContainerState(actualName, running, status, startedAt, List.copyOf(bindings));
    }

    public MutationResult start(String containerName) { return mutate(containerName, "start", null); }

    public MutationResult stop(String containerName, Duration timeout) {
        return mutate(containerName, "stop", "?t=" + Math.max(1, timeout.toSeconds()));
    }

    public MutationResult restart(String containerName, Duration timeout) {
        return mutate(containerName, "restart", "?t=" + Math.max(1, timeout.toSeconds()));
    }

    /**
     * Execute one fixed command inside a running container through Docker's exec API.
     * The caller controls the argv array; no shell is introduced here.
     */
    public ExecResult exec(String containerName, List<String> command, Duration timeout) {
        if (command == null || command.isEmpty()) throw new IllegalArgumentException("Docker exec command is required");
        Duration effectiveTimeout = timeout == null || timeout.isZero() || timeout.isNegative()
                ? Duration.ofSeconds(15) : timeout;

        JsonObject createBody = new JsonObject();
        createBody.addProperty("AttachStdin", false);
        createBody.addProperty("AttachStdout", true);
        createBody.addProperty("AttachStderr", true);
        // TTY makes the attached response a normal text stream instead of Docker's multiplexed binary framing.
        createBody.addProperty("Tty", true);
        JsonArray cmd = new JsonArray();
        command.forEach(cmd::add);
        createBody.add("Cmd", cmd);

        String createPath = versioned("/containers/" + encodePathSegment(containerName) + "/exec");
        var create = sendUnchecked("POST", createPath, Duration.ofSeconds(5), createBody.toString());
        if (create.statusCode() == 404) throw new IllegalStateException("Docker container not found for exec: " + containerName);
        require2xx(create, "create exec in " + containerName);
        JsonObject createJson = JsonParser.parseString(create.body()).getAsJsonObject();
        String execId = getString(createJson, "Id", null);
        if (execId == null || execId.isBlank()) throw new IllegalStateException("Docker exec create did not return an Id");

        JsonObject startBody = new JsonObject();
        startBody.addProperty("Detach", false);
        startBody.addProperty("Tty", true);
        String execPath = versioned("/exec/" + encodePathSegment(execId));
        var started = sendUnchecked("POST", execPath + "/start", effectiveTimeout, startBody.toString());
        require2xx(started, "start exec " + execId);

        var inspected = sendUnchecked("GET", execPath + "/json", Duration.ofSeconds(5));
        require2xx(inspected, "inspect exec " + execId);
        JsonObject inspectJson = JsonParser.parseString(inspected.body()).getAsJsonObject();
        if (getBoolean(inspectJson, "Running", false)) {
            throw new IllegalStateException("Docker exec is still running after attached start returned: " + execId);
        }
        int exitCode = getInt(inspectJson, "ExitCode", -1);
        return new ExecResult(exitCode, started.body() == null ? "" : started.body());
    }

    private MutationResult mutate(String containerName, String operation, String query) {
        String path = versioned("/containers/" + encodePathSegment(containerName) + "/" + operation)
                + (query == null ? "" : query);
        var response = sendUnchecked("POST", path, Duration.ofSeconds(65));
        if (response.statusCode() == 204) return new MutationResult(true, operation + " accepted");
        if (response.statusCode() == 304) return new MutationResult(false, "container already in requested state");
        require2xx(response, operation + " " + containerName);
        return new MutationResult(true, operation + " accepted");
    }

    private String versioned(String path) {
        String prefix = apiPrefix;
        if (prefix == null) {
            synchronized (this) {
                if (apiPrefix == null) {
                    var response = sendUnchecked("GET", "/version", Duration.ofSeconds(5));
                    require2xx(response, "Docker API version negotiation");
                    JsonObject root = JsonParser.parseString(response.body()).getAsJsonObject();
                    String apiVersion = getString(root, "ApiVersion", null);
                    if (apiVersion == null || apiVersion.isBlank()) throw new IllegalStateException("Docker /version did not return ApiVersion");
                    apiPrefix = "/v" + apiVersion;
                }
                prefix = apiPrefix;
            }
        }
        return prefix + path;
    }

    private HttpResponse<String> sendUnchecked(String method, String path, Duration timeout) {
        return sendUnchecked(method, path, timeout, null);
    }

    private HttpResponse<String> sendUnchecked(String method, String path, Duration timeout, String jsonBody) {
        try { return send(method, path, timeout, jsonBody); }
        catch (IOException e) { throw new IllegalStateException("Docker API I/O error calling " + path + ": " + e.getMessage(), e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while calling Docker API " + path, e); }
    }

    private HttpResponse<String> send(String method, String path, Duration timeout, String jsonBody) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(timeout).header("Accept", "application/json");
        if ("POST".equals(method)) {
            if (jsonBody == null) {
                builder.POST(HttpRequest.BodyPublishers.noBody());
            } else {
                builder.header("Content-Type", "application/json");
                builder.POST(HttpRequest.BodyPublishers.ofString(jsonBody, StandardCharsets.UTF_8));
            }
        } else {
            builder.GET();
        }
        return httpClient.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private static void require2xx(HttpResponse<String> response, String operation) {
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            String body = response.body() == null ? "" : response.body();
            if (body.length() > 500) body = body.substring(0, 500) + "...";
            throw new IllegalStateException(operation + " failed: HTTP " + response.statusCode() + " body=" + body);
        }
    }

    private static String encodePathSegment(String value) { return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20"); }
    private static String stripTrailingSlash(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("dockerApiBaseUrl is required");
        return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
    }
    private static String getString(JsonObject o, String p, String d) { JsonElement e=o.get(p); return e==null||e.isJsonNull()?d:e.getAsString(); }
    private static boolean getBoolean(JsonObject o, String p, boolean d) { JsonElement e=o.get(p); return e==null||e.isJsonNull()?d:e.getAsBoolean(); }
    private static int getInt(JsonObject o, String p, int d) { JsonElement e=o.get(p); return e==null||e.isJsonNull()?d:e.getAsInt(); }
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank() || value.startsWith("0001-")) return null;
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) { return null; }
    }

    public record MutationResult(boolean changed, String message) {}
    public record ExecResult(int exitCode, String output) {}
}
