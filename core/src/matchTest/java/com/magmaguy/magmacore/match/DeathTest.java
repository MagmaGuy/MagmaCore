package com.magmaguy.magmacore.match;

import org.bukkit.GameMode;
import org.bukkit.Material;
import org.bukkit.block.Block;
import org.bukkit.event.block.BlockDamageEvent;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.potion.PotionEffectType;
import org.junit.jupiter.api.Test;
import org.mockbukkit.mockbukkit.entity.PlayerMock;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.*;

class DeathTest extends MatchTestSupport {
    private TestMatch ongoing(Consumer<MatchSettings.Builder> customize, PlayerMock... players) {
        TestMatch match = openMatch(settings -> {
            settings.countdownSeconds(0).players(1, 4);
            customize.accept(settings);
        });
        match.admit(List.of(players));
        server.getScheduler().performTicks(1);
        // A real server always has the chunk a player dies in loaded; setLocation does not load it.
        arena.loadChunk(0, 0);
        return match;
    }

    private EntityDamageEvent lethal(PlayerMock player, EntityDamageEvent.DamageCause cause) {
        EntityDamageEvent event = new EntityDamageEvent(player, cause, 1000);
        server.getPluginManager().callEvent(event);
        return event;
    }

    private void punch(PlayerMock puncher, Block block) {
        server.getPluginManager().callEvent(
                new BlockDamageEvent(puncher, block, puncher.getInventory().getItemInMainHand(), false));
    }

    @Test
    void lethalDamageIsCancelledAndEliminates() {
        var alex = player("Alex");
        var bea = player("Bea");
        TestMatch match = ongoing(settings -> settings.death(DeathPolicy.eliminate()), alex, bea);
        alex.setFireTicks(100);
        alex.addPotionEffect(new PotionEffect(PotionEffectType.SPEED, 200, 1));

        assertTrue(lethal(alex, EntityDamageEvent.DamageCause.CUSTOM).isCancelled());

        assertEquals(0, alex.getFireTicks());
        assertTrue(alex.getActivePotionEffects().isEmpty());
        assertTrue(match.hooks.containsAll(List.of("onDeath:Alex", "onLeave:Alex:DIED")));
        assertEquals(MatchPhase.ONGOING, match.getPhase());

        lethal(bea, EntityDamageEvent.DamageCause.CUSTOM);
        assertEquals(MatchOutcome.DEFEAT, match.getOutcome());
    }

    @Test
    void survivableDamageIsLeftAlone() {
        var alex = player("Alex");
        ongoing(settings -> { }, alex);
        EntityDamageEvent event = new EntityDamageEvent(alex, EntityDamageEvent.DamageCause.CUSTOM, 2);
        server.getPluginManager().callEvent(event);
        assertFalse(event.isCancelled());
    }

    @Test
    void voidDamageRescuesInsteadOfKilling() {
        var alex = player("Alex");
        TestMatch match = ongoing(settings -> { }, alex);
        alex.setLocation(at(arena, 0.5, arena.getMinHeight() - 70, 0.5));
        assertTrue(lethal(alex, EntityDamageEvent.DamageCause.VOID).isCancelled());
        assertSame(match, MatchCore.matchOf(alex));
        assertEquals(at(arena, 0.5, 64, 0.5), alex.getLocation());
    }

    @Test
    void dyingBeforeTheMatchStartsRemovesThePlayer() {
        TestMatch match = openMatch(settings -> settings.players(2, 4));
        var alex = player("Alex");
        match.admit(List.of(alex, player("Bea")));
        server.getScheduler().performTicks(1);
        assertTrue(lethal(alex, EntityDamageEvent.DamageCause.CUSTOM).isCancelled());
        assertNull(MatchCore.matchOf(alex));
        assertTrue(match.hooks.contains("onLeave:Alex:DIED"));
    }

    @Test
    void spectateAndReviveLeavesABannerThatRevivesWhenPunched() {
        var alex = player("Alex");
        var bea = player("Bea");
        List<String> marker = new ArrayList<>();
        ReviveMarker recording = new ReviveMarker() {
            @Override
            public void show(Block banner, MatchPlayer dead, int livesLeft) {
                marker.add("show:" + livesLeft);
            }

            @Override
            public void hide() {
                marker.add("hide");
            }
        };
        TestMatch match = ongoing(settings -> settings.death(DeathPolicy.spectateAndRevive(3, participant -> recording)), alex, bea);
        alex.setLocation(at(arena, 10.5, 64, 10.5));

        lethal(alex, EntityDamageEvent.DamageCause.CUSTOM);

        MatchPlayer dead = match.participant(alex);
        assertEquals(MatchRole.SPECTATOR, dead.getRole());
        assertEquals(GameMode.SPECTATOR, alex.getGameMode());
        Block banner = arena.getBlockAt(10, 64, 10);
        assertEquals(Material.RED_BANNER, banner.getType());
        assertEquals(List.of("show:3"), marker);

        punch(bea, banner);

        assertEquals(MatchRole.PLAYER, dead.getRole());
        assertEquals(2, dead.getLives());
        assertEquals(GameMode.SURVIVAL, alex.getGameMode());
        assertEquals(at(arena, 10.5, 64, 10.5), alex.getLocation());
        assertNotEquals(Material.RED_BANNER, banner.getType());
        assertEquals(List.of("show:3", "hide"), marker);
    }

    @Test
    void aBannerRemovedByPhysicsIsPutBack() {
        var alex = player("Alex");
        var bea = player("Bea");
        ongoing(settings -> settings.death(DeathPolicy.spectateAndRevive(3)), alex, bea);
        alex.setLocation(at(arena, 10.5, 64, 10.5));
        lethal(alex, EntityDamageEvent.DamageCause.CUSTOM);
        Block banner = arena.getBlockAt(10, 64, 10);
        banner.setType(Material.AIR);
        server.getScheduler().performTicks(5);
        assertEquals(Material.RED_BANNER, arena.getBlockAt(10, 64, 10).getType());
    }

    @Test
    void theLastActivePlayerDyingEndsTheMatchEvenWithRevivableSpectators() {
        var alex = player("Alex");
        var bea = player("Bea");
        TestMatch match = ongoing(settings -> settings.death(DeathPolicy.spectateAndRevive(3)), alex, bea);
        lethal(alex, EntityDamageEvent.DamageCause.CUSTOM);
        lethal(bea, EntityDamageEvent.DamageCause.CUSTOM);
        assertEquals(MatchOutcome.DEFEAT, match.getOutcome());
        assertTrue(match.hooks.contains("onLeave:Bea:DIED"));
    }

    @Test
    void aPlayerWithoutLivesLeftGetsNoBanner() {
        var alex = player("Alex");
        var bea = player("Bea");
        TestMatch match = ongoing(settings -> settings.death(DeathPolicy.spectateAndRevive(1)), alex, bea);
        alex.setLocation(at(arena, 10.5, 64, 10.5));
        lethal(alex, EntityDamageEvent.DamageCause.CUSTOM);
        punch(bea, arena.getBlockAt(10, 64, 10));
        // One life: revived once, then the second death leaves no banner.
        assertEquals(0, match.participant(alex).getLives());
        lethal(alex, EntityDamageEvent.DamageCause.CUSTOM);
        assertNotEquals(Material.RED_BANNER, arena.getBlockAt(10, 64, 10).getType());
        assertEquals(MatchRole.SPECTATOR, match.participant(alex).getRole());
    }

    @Test
    void leavingClearsTheLeaversBanner() {
        var alex = player("Alex");
        var bea = player("Bea");
        TestMatch match = ongoing(settings -> settings.death(DeathPolicy.spectateAndRevive(3)), alex, bea);
        alex.setLocation(at(arena, 10.5, 64, 10.5));
        lethal(alex, EntityDamageEvent.DamageCause.CUSTOM);
        match.leave(alex, LeaveReason.QUIT);
        assertNotEquals(Material.RED_BANNER, arena.getBlockAt(10, 64, 10).getType());
        assertEquals(GameMode.SURVIVAL, alex.getGameMode());
    }
}
