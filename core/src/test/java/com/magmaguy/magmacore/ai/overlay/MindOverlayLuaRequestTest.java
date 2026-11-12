package com.magmaguy.magmacore.ai.overlay;

import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaError;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** What a script's {@code mob:override_ai(behaviors, options)} arguments become. */
class MindOverlayLuaRequestTest {
    private static final UUID OWNER = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

    @Test
    void oneNameIsOneBehaviorThatServesNobodyUntilCleared() {
        MindOverlayRequest request = MindOverlayRequest.fromLua(LuaValue.valueOf("Guardian"));
        assertEquals(List.of("guardian"), request.behaviors());
        assertNull(request.owner());
        assertEquals(0L, request.durationTicks());
        assertTrue(request.settings().isEmpty());
    }

    @Test
    void aListNamesSeveralBehaviorsInOrder() {
        LuaTable names = LuaValue.listOf(new LuaValue[]{LuaValue.valueOf("coward"), LuaValue.valueOf("wander")});
        assertEquals(List.of("coward", "wander"), MindOverlayRequest.fromLua(names).behaviors());
    }

    @Test
    void theOwnerIsAnEntityTableOrItsUuid() {
        LuaTable entity = new LuaTable();
        entity.set("uuid", OWNER.toString());
        LuaTable byTable = new LuaTable();
        byTable.set("owner", entity);
        assertEquals(OWNER, MindOverlayRequest.fromLua(
                LuaValue.varargsOf(LuaValue.valueOf("guardian"), byTable)).owner());

        LuaTable byUuid = new LuaTable();
        byUuid.set("owner", OWNER.toString());
        assertEquals(OWNER, MindOverlayRequest.fromLua(
                LuaValue.varargsOf(LuaValue.valueOf("guardian"), byUuid)).owner());
    }

    @Test
    void durationIsInTicksAndSettingsKeepTheirPlainTypes() {
        LuaTable guardian = new LuaTable();
        guardian.set("chase_speed", 1.6);
        guardian.set("assist", LuaValue.FALSE);
        guardian.set("cry", "ENTITY_WOLF_GROWL");
        guardian.set("kinds", LuaValue.listOf(new LuaValue[]{LuaValue.valueOf("zombie"), LuaValue.valueOf("husk")}));
        LuaTable settings = new LuaTable();
        settings.set("Guardian", guardian);
        LuaTable options = new LuaTable();
        options.set("duration", 600);
        options.set("settings", settings);

        MindOverlayRequest request = MindOverlayRequest.fromLua(
                LuaValue.varargsOf(LuaValue.valueOf("guardian"), options));
        assertEquals(600L, request.durationTicks());
        assertEquals(Map.of("guardian", Map.of("chase_speed", 1.6D, "assist", false, "cry", "ENTITY_WOLF_GROWL",
                "kinds", List.of("zombie", "husk"))), request.settings());
    }

    @Test
    void aNegativeDurationLastsUntilCleared() {
        LuaTable options = new LuaTable();
        options.set("duration", -5);
        assertEquals(0L, MindOverlayRequest.fromLua(
                LuaValue.varargsOf(LuaValue.valueOf("guardian"), options)).durationTicks());
    }

    @Test
    void argumentsOfAnotherShapeAreRefused() {
        assertThrows(IllegalArgumentException.class, () -> MindOverlayRequest.fromLua(new LuaTable()));
        assertThrows(LuaError.class, () -> MindOverlayRequest.fromLua(LuaValue.valueOf(true)));
        LuaTable badOwner = new LuaTable();
        badOwner.set("owner", "not-a-uuid");
        assertThrows(IllegalArgumentException.class, () -> MindOverlayRequest.fromLua(
                LuaValue.varargsOf(LuaValue.valueOf("guardian"), badOwner)));
    }
}
