package com.example.minecraftcontrol.bedrock;

import com.example.minecraftcontrol.config.MinecraftProperties;
import org.springframework.stereotype.Component;

import java.net.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Duration;
import java.util.Arrays;

@Component
public class BedrockStatusClient {
    static final byte[] OFFLINE_MESSAGE_DATA_ID = new byte[]{
            0x00, (byte)0xff, (byte)0xff, 0x00,
            (byte)0xfe, (byte)0xfe, (byte)0xfe, (byte)0xfe,
            (byte)0xfd, (byte)0xfd, (byte)0xfd, (byte)0xfd,
            0x12, 0x34, 0x56, 0x78
    };

    private final String host;
    private final int port;
    private final Duration timeout;
    private final long clientGuid = new SecureRandom().nextLong();

    public BedrockStatusClient(MinecraftProperties properties) {
        this.host = properties.bedrockHost();
        this.port = properties.bedrockPort();
        this.timeout = properties.pingTimeout();
    }

    public BedrockStatus query() {
        try (DatagramSocket socket = new DatagramSocket()) {
            socket.setSoTimeout((int)Math.max(100, timeout.toMillis()));
            InetAddress address = InetAddress.getByName(host);
            byte[] request = buildPing(System.currentTimeMillis(), clientGuid);
            socket.send(new DatagramPacket(request, request.length, address, port));
            byte[] responseBytes = new byte[4096];
            DatagramPacket response = new DatagramPacket(responseBytes, responseBytes.length);
            socket.receive(response);
            return parsePong(Arrays.copyOf(response.getData(), response.getLength()));
        } catch (SocketTimeoutException e) {
            return BedrockStatus.unavailable("timeout");
        } catch (Exception e) {
            return BedrockStatus.unavailable(e.getClass().getSimpleName() + ": " + e.getMessage());
        }
    }

    static byte[] buildPing(long timestamp, long clientGuid) {
        ByteBuffer out = ByteBuffer.allocate(33).order(ByteOrder.BIG_ENDIAN);
        out.put((byte)0x01).putLong(timestamp).put(OFFLINE_MESSAGE_DATA_ID).putLong(clientGuid);
        return out.array();
    }

    static BedrockStatus parsePong(byte[] data) {
        try {
            if (data.length < 35) return BedrockStatus.unavailable("pong too short");
            ByteBuffer in = ByteBuffer.wrap(data).order(ByteOrder.BIG_ENDIAN);
            int packetId = Byte.toUnsignedInt(in.get());
            if (packetId != 0x1c) return BedrockStatus.unavailable("unexpected packet id " + packetId);
            in.getLong(); in.getLong();
            byte[] magic = new byte[16]; in.get(magic);
            if (!Arrays.equals(magic, OFFLINE_MESSAGE_DATA_ID)) return BedrockStatus.unavailable("invalid RakNet magic");
            int length = Short.toUnsignedInt(in.getShort());
            if (length > in.remaining()) return BedrockStatus.unavailable("invalid server-id length " + length);
            byte[] idBytes = new byte[length]; in.get(idBytes);
            String serverId = new String(idBytes, StandardCharsets.UTF_8);
            String[] f = serverId.split(";", -1);
            if (f.length < 6 || !"MCPE".equals(f[0])) return BedrockStatus.unavailable("unexpected Bedrock server-id");
            return new BedrockStatus(true, f[1], parseInt(f[2], -1), f[3], parseInt(f[4], -1), parseInt(f[5], -1), f.length > 8 ? f[8] : null, null);
        } catch (Exception e) {
            return BedrockStatus.unavailable("parse error: " + e.getMessage());
        }
    }

    private static int parseInt(String value, int fallback) {
        try { return Integer.parseInt(value); } catch (NumberFormatException e) { return fallback; }
    }
}
