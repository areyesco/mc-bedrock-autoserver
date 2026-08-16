package com.example.minecraftcontrol.web;

import com.example.minecraftcontrol.config.MinecraftProperties;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpMethod;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

@Component
public class McpTokenFilter extends OncePerRequestFilter {
    static final String HEADER = "X-Minecraft-MCP-Token";
    private final byte[] expected;

    public McpTokenFilter(MinecraftProperties properties) {
        String token = properties.mcpSharedToken();
        this.expected = token == null || token.isBlank() ? null : token.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !request.getRequestURI().startsWith("/mcp") || expected == null;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String supplied = request.getHeader(HEADER);
        byte[] actual = supplied == null ? new byte[0] : supplied.getBytes(StandardCharsets.UTF_8);
        if (!MessageDigest.isEqual(expected, actual)) {
            response.sendError(HttpServletResponse.SC_UNAUTHORIZED, "Missing or invalid MCP token");
            return;
        }
        // Spring AI keeps the optional standalone SSE GET open without committing
        // response headers. Clients that synchronously probe that stream can then
        // block before sending notifications/initialized. MCP permits servers that
        // do not offer this optional stream to answer GET with 405.
        if (HttpMethod.GET.matches(request.getMethod())) {
            response.setHeader("Allow", "POST, DELETE");
            response.setStatus(HttpServletResponse.SC_METHOD_NOT_ALLOWED);
            return;
        }
        filterChain.doFilter(request, response);
    }
}
