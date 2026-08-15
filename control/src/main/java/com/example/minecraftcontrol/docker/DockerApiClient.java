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
            var response = send("GET", "/_ping", Duration.ofSeconds(3));
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
        try { return send(method, path, timeout); }
        catch (IOException e) { throw new IllegalStateException("Docker API I/O error calling " + path + ": " + e.getMessage(), e); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new IllegalStateException("Interrupted while calling Docker API " + path, e); }
    }

    private HttpResponse<String> send(String method, String path, Duration timeout) throws IOException, InterruptedException {
        HttpRequest.Builder builder = HttpRequest.newBuilder().uri(URI.create(baseUrl + path)).timeout(timeout).header("Accept", "application/json");
        if ("POST".equals(method)) builder.POST(HttpRequest.BodyPublishers.noBody()); else builder.GET();
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
    private static Instant parseInstant(String value) {
        if (value == null || value.isBlank() || value.startsWith("0001-")) return null;
        try { return Instant.parse(value); } catch (DateTimeParseException ignored) { return null; }
    }

    public record MutationResult(boolean changed, String message) {}
}
