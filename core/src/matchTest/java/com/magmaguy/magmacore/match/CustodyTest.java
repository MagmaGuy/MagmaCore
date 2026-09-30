package com.magmaguy.magmacore.match;

import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.inventory.ItemStack;
import org.bukkit.persistence.PersistentDataType;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class CustodyTest extends MatchTestSupport {
    private static final NamespacedKey KEY = new NamespacedKey("matchtest", "custody_v1");
    // MockBukkit cannot write playerdata, so flushes are recorded instead.
    private final List<String> flushes = new ArrayList<>();

    private PlayerCustody custody(NamespacedKey key) {
        return new SnapshotCustody(key, player -> flushes.add(player.getName()
                + (player.getPersistentDataContainer().has(key, PersistentDataType.BYTE_ARRAY) ? ":leased" : ":free")));
    }

    private byte[] lease(org.bukkit.entity.Player player, NamespacedKey key) {
        return player.getPersistentDataContainer().get(key, PersistentDataType.BYTE_ARRAY);
    }

    @Test
    void snapshotRestoresInventoryAndFlightWhenLeaving() {
        PlayerCustody custody = custody(KEY);
        TestMatch match = openMatch(settings -> settings.custody(custody).players(1, 4));
        var alex = player("Alex");
        alex.getInventory().setItem(0, new ItemStack(Material.DIAMOND, 3));
        alex.setAllowFlight(true);
        match.admit(List.of(alex, player("Bea")));
        assertNotNull(lease(alex, KEY), "custody is taken at admission");
        assertEquals(List.of("Alex:leased", "Bea:leased"), flushes, "each lease is flushed before the match changes anything");
        alex.getInventory().clear();
        alex.getInventory().setItem(0, new ItemStack(Material.STICK));
        alex.setAllowFlight(false);

        match.leave(alex, LeaveReason.QUIT);

        assertEquals(new ItemStack(Material.DIAMOND, 3), alex.getInventory().getItem(0));
        assertTrue(alex.getAllowFlight());
        assertNull(lease(alex, KEY));
        // Restored state is flushed while the lease still exists, then again without it.
        assertEquals(List.of("Alex:leased", "Bea:leased", "Alex:leased", "Alex:free"), flushes);
    }

    @Test
    void aLeaseLeftByACrashRestoresOnTheNextJoin() {
        PlayerCustody custody = custody(KEY);
        MatchCore.registerCustody(custody);
        var alex = player("Alex");
        alex.getInventory().setItem(0, new ItemStack(Material.EMERALD));
        custody.capture(alex);
        alex.getInventory().clear();
        alex.teleport(arena.getSpawnLocation());

        server.getPluginManager().callEvent(new PlayerJoinEvent(alex, "joined"));

        assertEquals(new ItemStack(Material.EMERALD), alex.getInventory().getItem(0));
        assertEquals(overworld, alex.getWorld(), "recovery returns the player to where custody began");
        assertNull(lease(alex, KEY));
    }

    @Test
    void eternalTdLeasesStillRestore() throws IOException {
        NamespacedKey eternalKey = new NamespacedKey("eternaltd", "match_state_v1");
        MatchCore.registerCustody(custody(eternalKey));
        var alex = player("Alex");
        ItemStack[] contents = new ItemStack[alex.getInventory().getContents().length];
        contents[0] = new ItemStack(Material.GOLD_INGOT, 7);
        alex.getPersistentDataContainer().set(eternalKey, PersistentDataType.BYTE_ARRAY,
                EternalTdPayload.encode(contents, 0, 0.1f, false, false, overworld.getSpawnLocation()));

        server.getPluginManager().callEvent(new PlayerJoinEvent(alex, "joined"));

        assertEquals(new ItemStack(Material.GOLD_INGOT, 7), alex.getInventory().getItem(0));
        assertNull(lease(alex, eternalKey));
    }

    @Test
    void theFactoryUsesTheSnapshotCustody() {
        assertInstanceOf(SnapshotCustody.class, PlayerCustody.snapshot(KEY));
    }

    @Test
    void aCorruptLeaseKicksThePlayerAndIsKept() {
        MatchCore.registerCustody(custody(KEY));
        var alex = player("Alex");
        alex.getPersistentDataContainer().set(KEY, PersistentDataType.BYTE_ARRAY, new byte[]{1, 2, 3});

        server.getPluginManager().callEvent(new PlayerJoinEvent(alex, "joined"));

        assertFalse(alex.isOnline());
        assertNotNull(lease(alex, KEY));
    }

    @Test
    void anExistingLeaseBlocksAdmission() {
        PlayerCustody custody = custody(KEY);
        TestMatch match = openMatch(settings -> settings.custody(custody));
        var alex = player("Alex");
        custody.capture(alex);
        assertEquals(AdmissionResult.FAILED, match.admit(List.of(alex)));
        assertNull(MatchCore.matchOf(alex));
        assertTrue(alex.getMetadata(MatchCore.MARKER_KEY).isEmpty());
        assertTrue(match.hooks.contains("abortAdmission"));
    }

    @Test
    void aFailedCaptureReleasesTheMembersAlreadyCaptured() {
        PlayerCustody custody = custody(KEY);
        TestMatch match = openMatch(settings -> settings.custody(custody).players(1, 4));
        var alex = player("Alex");
        var bea = player("Bea");
        custody.capture(bea);
        assertEquals(AdmissionResult.FAILED, match.admit(List.of(alex, bea)));
        assertNull(lease(alex, KEY), "Alex's lease is released when Bea's capture fails");
    }
}
