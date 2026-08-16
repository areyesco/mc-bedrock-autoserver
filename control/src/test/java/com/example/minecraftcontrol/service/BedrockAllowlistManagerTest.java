package com.example.minecraftcontrol.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class BedrockAllowlistManagerTest {

    @Test
    void normalizesCommonGamertags() {
        assertEquals("Alex 123", BedrockAllowlistManager.normalizeGamertag("  Alex 123  "));
        assertEquals("Alex#1234", BedrockAllowlistManager.normalizeGamertag("Alex#1234"));
        assertEquals("Player_Name-1", BedrockAllowlistManager.normalizeGamertag("Player_Name-1"));
    }

    @Test
    void rejectsMissingOrUnsafeGamertags() {
        assertThrows(IllegalArgumentException.class, () -> BedrockAllowlistManager.normalizeGamertag(null));
        assertThrows(IllegalArgumentException.class, () -> BedrockAllowlistManager.normalizeGamertag("   "));
        assertThrows(IllegalArgumentException.class, () -> BedrockAllowlistManager.normalizeGamertag("Bad\nName"));
        assertThrows(IllegalArgumentException.class, () -> BedrockAllowlistManager.normalizeGamertag("Bad\"Name"));
        assertThrows(IllegalArgumentException.class, () -> BedrockAllowlistManager.normalizeGamertag("Bad\\Name"));
    }
}
