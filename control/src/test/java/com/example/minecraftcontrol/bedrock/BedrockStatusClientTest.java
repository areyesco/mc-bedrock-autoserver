package com.example.minecraftcontrol.bedrock;

import org.junit.jupiter.api.Test;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import static org.junit.jupiter.api.Assertions.*;

class BedrockStatusClientTest {
    @Test
    void parsesRakNetBedrockPong() {
        String id = "MCPE;Minecraft Familiar;827;1.21.100;2;10;123456;Mundo Familiar;Survival;1;19132;19133;";
        byte[] text = id.getBytes(StandardCharsets.UTF_8);
        ByteBuffer b = ByteBuffer.allocate(1 + 8 + 8 + 16 + 2 + text.length).order(ByteOrder.BIG_ENDIAN);
        b.put((byte)0x1c).putLong(123L).putLong(456L).put(BedrockStatusClient.OFFLINE_MESSAGE_DATA_ID).putShort((short)text.length).put(text);
        BedrockStatus s = BedrockStatusClient.parsePong(b.array());
        assertTrue(s.responding());
        assertEquals("Minecraft Familiar", s.motd());
        assertEquals(2, s.players());
        assertEquals(10, s.maxPlayers());
        assertEquals("Survival", s.gameMode());
    }

    @Test
    void buildsCorrectPing() {
        byte[] ping = BedrockStatusClient.buildPing(1L, 2L);
        assertEquals(33, ping.length);
        assertEquals(0x01, Byte.toUnsignedInt(ping[0]));
    }
}
