package com.magmaguy.magmacore.scripting.tables;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class LuaWorldTableTest {

    @Test
    void normalizesCustomResourcePackSoundKeys() {
        assertEquals("elitemobs:boss.roar",
                LuaWorldTable.normalizeCustomSoundKey("  ELITEMOBS:BOSS.ROAR  "));
        assertEquals("minecraft:custom_sound",
                LuaWorldTable.normalizeCustomSoundKey("CUSTOM_SOUND"));
    }

    @Test
    void rejectsInvalidCustomResourcePackSoundKeys() {
        assertNull(LuaWorldTable.normalizeCustomSoundKey(null));
        assertNull(LuaWorldTable.normalizeCustomSoundKey(" "));
        assertNull(LuaWorldTable.normalizeCustomSoundKey("not a sound"));
    }
}
