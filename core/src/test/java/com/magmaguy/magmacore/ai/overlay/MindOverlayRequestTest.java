package com.magmaguy.magmacore.ai.overlay;

import com.magmaguy.magmacore.scripting.LuaMindModuleDescriptor;
import org.junit.jupiter.api.Test;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MindOverlayRequestTest {
    private static final UUID OWNER = UUID.fromString("0f0e0d0c-0b0a-4908-8706-050403020100");

    @Test
    void behaviorNamesAreFileNamesWhateverTheirCaseOrExtension() {
        MindOverlayRequest request = MindOverlayRequest.of(List.of("Guard.lua", " coward ", "guard"));
        assertEquals(List.of("guard", "coward"), request.behaviors());
        assertNull(request.owner());
        assertEquals(0L, request.durationTicks());
    }

    @Test
    void anOverlayNeedsABehaviorAndATimeThatIsNotNegative() {
        assertThrows(IllegalArgumentException.class, () -> MindOverlayRequest.of(List.of()));
        assertThrows(IllegalArgumentException.class, () -> MindOverlayRequest.of(List.of("  ")));
        assertThrows(IllegalArgumentException.class, () -> MindOverlayRequest.of(List.of("guard")).lasting(-1L));
    }

    @Test
    void aRequestIsWrittenOnItsMobAndReadBackWithItsEnd() {
        MindOverlayRequest request = MindOverlayRequest.of(List.of("guard", "pack.coward"))
                .ownedBy(OWNER)
                .withSettings(Map.of("pack.coward", Map.of("radius", 12D, "kinds", List.of("player", "zombie")),
                        "guard", Map.of("leash", true, "cry", "ENTITY_WOLF_GROWL")));
        Optional<MindOverlayRecord> read = MindOverlayRecord.decode(new MindOverlayRecord(request, 4321L).encode());
        assertTrue(read.isPresent());
        assertEquals(4321L, read.get().endsAt());
        assertEquals(request.behaviors(), read.get().request().behaviors());
        assertEquals(OWNER, read.get().request().owner());
        assertEquals(request.settings(), read.get().request().settings());
    }

    @Test
    void anOverlayWithoutOwnerOrEndIsWrittenWithoutThem() {
        String written = new MindOverlayRecord(MindOverlayRequest.of(List.of("guard")), 0L).encode();
        MindOverlayRecord read = MindOverlayRecord.decode(written).orElseThrow();
        assertNull(read.request().owner());
        assertEquals(0L, read.endsAt());
        assertTrue(read.request().settings().isEmpty());
    }

    @Test
    void whatIsNotARecordReadsAsNone() {
        assertTrue(MindOverlayRecord.decode("").isEmpty());
        assertTrue(MindOverlayRecord.decode("behaviors: []").isEmpty());
        assertTrue(MindOverlayRecord.decode("behaviors: [guard]\nowner: not-a-uuid").isEmpty());
        assertTrue(MindOverlayRecord.decode("behaviors: [guard\n").isEmpty());
    }

    @Test
    void aSettingReplacesItsDefaultAsTheTypeOfThatDefault() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("radius", 12);
        overrides.put("speed", "1.5");
        overrides.put("calm", "false");
        overrides.put("cry", 7);
        overrides.put("kinds", "zombie");
        Map<String, Map<String, Object>> merged = MindOverlaySettings.merge(
                Map.of("test:mob/coward", coward()), "test:mob/", Map.of("coward", overrides), warnings::add);
        assertEquals(Map.of("radius", 12D, "speed", 1.5D, "calm", false, "cry", "7", "kinds", List.of("zombie")),
                merged.get("test:mob/coward"));
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    void aSettingThatCannotBeItsDefaultsTypeKeepsTheDefaultAndSaysSo() {
        List<String> warnings = new ArrayList<>();
        Map<String, Object> overrides = new LinkedHashMap<>();
        overrides.put("radius", "far");
        overrides.put("calm", 1);
        overrides.put("cry", List.of("a"));
        overrides.put("reach", 3);
        Map<String, Map<String, Object>> merged = MindOverlaySettings.merge(
                Map.of("test:mob/coward", coward()), "test:mob/",
                Map.of("coward", overrides, "guard", Map.of("leash", true)), warnings::add);
        assertEquals(coward().settings(), merged.get("test:mob/coward"));
        assertEquals(5, warnings.size(), warnings.toString());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("'reach'")), warnings.toString());
        assertTrue(warnings.stream().anyMatch(warning -> warning.contains("'guard'")), warnings.toString());
    }

    @Test
    void settingsReachLuaAsNumbersBooleansTextAndLists() {
        LuaTable table = MindOverlaySettings.toLua(coward().settings());
        assertEquals(8D, table.get("radius").todouble());
        assertTrue(table.get("calm").toboolean());
        assertEquals("ENTITY_FOX_SCREECH", table.get("cry").tojstring());
        assertEquals(LuaValue.valueOf("player"), table.get("kinds").get(1));
        assertEquals(1, table.get("kinds").length());
    }

    private static LuaMindModuleDescriptor coward() {
        return new LuaMindModuleDescriptor("test:mob/coward", 1L, List.of(), Map.of(
                "radius", 8D, "speed", 1.2D, "calm", true, "cry", "ENTITY_FOX_SCREECH", "kinds", List.of("player")));
    }
}
