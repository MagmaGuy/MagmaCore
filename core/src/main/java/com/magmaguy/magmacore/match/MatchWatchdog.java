package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

/**
 * One task per open match, running every tick: disconnects, void and boundary rescue, the
 * spectator camera guard, intruders and the waiting reminder. Ported from EliteMobs'
 * MatchInstance watchdogs.
 */
final class MatchWatchdog implements Runnable {
    private static final long WAITING_HINT_PERIOD_TICKS = 20L * 60L;
    private static final int RESCUE_ATTEMPTS_BEFORE_FALLBACK = 3;
    private static final int RESCUE_LOOP_WARNING_THRESHOLD = 10;
    private final Match match;
    private BukkitTask task;
    private long ticks;

    private MatchWatchdog(Match match) {
        this.match = match;
    }

    static MatchWatchdog start(Match match) {
        MatchWatchdog watchdog = new MatchWatchdog(match);
        watchdog.task = Bukkit.getScheduler().runTaskTimer(MatchCore.plugin(), watchdog, 0L, 1L);
        return watchdog;
    }

    /**
     * Pulls a participant back from the void or from outside the space. Prefers the last block
     * they stood on: a fixed rescue point loops forever when the ground under it is missing,
     * so the start is used only after repeated failures, and a persistent loop is logged so the
     * broken geometry can be found.
     */
    static void rescue(Match match, MatchPlayer participant) {
        Player player = participant.getPlayer();
        int attempts = ++participant.consecutiveRescues;
        Location destination = participant.lastSafeLocation;
        if (destination == null || attempts > RESCUE_ATTEMPTS_BEFORE_FALLBACK)
            destination = match.startDestination();

        if (attempts == RESCUE_LOOP_WARNING_THRESHOLD && destination != null && destination.getWorld() != null)
            Logger.warn("Player " + player.getName() + " has been rescued from the void " + attempts
                    + " times in a row in match world '" + destination.getWorld().getName()
                    + "'. The ground at the rescue point is probably missing, which would otherwise loop forever. "
                    + "Check the geometry around " + destination.getBlockX() + "," + destination.getBlockY()
                    + "," + destination.getBlockZ() + ".");

        if (destination == null) return;
        // Without this the fall distance built up before the rescue kills the player on landing.
        player.setFallDistance(0);
        MatchMovement.moveForMatch(match, player, participant, destination, MoveReason.RESCUE);
    }

    void cancel() {
        if (task != null) task.cancel();
        task = null;
    }

    @Override
    public void run() {
        if (match.isDestroyed()) {
            cancel();
            return;
        }
        for (MatchPlayer participant : match.getMatchPlayers()) {
            if (!participant.getPlayer().isOnline()) {
                match.leave(participant.getPlayer(), LeaveReason.DISCONNECT);
                continue;
            }
            if (match.getMatchPlayer(participant.getPlayer()) != participant || !participant.entered) continue;
            if (participant.role == MatchRole.PLAYER) watchPlayer(participant);
            else watchSpectator(participant);
        }
        if (match.phase == MatchPhase.STARTING || match.phase == MatchPhase.ONGOING) ejectIntruders();
        if (match.phase == MatchPhase.WAITING && ticks % WAITING_HINT_PERIOD_TICKS == 0) remindWaitingPlayers();
        ticks++;
    }

    private void watchPlayer(MatchPlayer participant) {
        Location location = participant.getPlayer().getLocation();
        // Temporary-world spaces span whole worlds, so falling into the void never leaves the
        // space; the height check is what catches it.
        if (isBelowWorld(location) || !match.contains(location)) {
            rescue(match, participant);
            return;
        }
        rememberSafeLocation(participant, location);
    }

    private void rememberSafeLocation(MatchPlayer participant, Location location) {
        if (!participant.getPlayer().isOnGround()) return;
        Location stored = participant.lastSafeLocation;
        if (stored == null
                || stored.getBlockX() != location.getBlockX()
                || stored.getBlockY() != location.getBlockY()
                || stored.getBlockZ() != location.getBlockZ()
                || stored.getWorld() != location.getWorld())
            participant.lastSafeLocation = location.clone();
        participant.consecutiveRescues = 0;
    }

    private void watchSpectator(MatchPlayer participant) {
        Player player = participant.getPlayer();
        // Spectating an entity attaches the camera without a cancellable teleport event, and the
        // server then drags the spectator's body to that entity anywhere on the server. Release
        // the camera unless it is on a fellow participant. It must be cleared before any
        // teleport: teleporting a spectator attached to an entity desyncs the client on
        // Paper <= 1.21.10 (PaperMC#13473).
        Entity target = player.getSpectatorTarget();
        if (target != null && !target.equals(player)) {
            boolean fellowParticipant = target instanceof Player targetPlayer && match.getMatchPlayer(targetPlayer) != null;
            // setSpectatorTarget throws for non-spectators; a tick must never die on one player.
            if (!fellowParticipant && player.getGameMode() == GameMode.SPECTATOR) player.setSpectatorTarget(null);
        }
        if (!match.contains(player.getLocation())) rescue(match, participant);
    }

    private void ejectIntruders() {
        String bypass = match.getSettings().getBypassPermission();
        for (World world : match.getSettings().getSpace().worlds()) {
            for (Player player : world.getPlayers()) {
                if (match.getMatchPlayer(player) != null) continue;
                if (bypass != null && player.hasPermission(bypass)) continue;
                if (!match.contains(player.getLocation())) continue;
                Location destination = null;
                try {
                    destination = match.intruderDestination(player);
                } catch (RuntimeException | LinkageError failure) {
                    Logger.warn("Match " + match.getRuntimeId() + ": intruderDestination failed: " + failure);
                }
                if (destination != null) MatchMovement.moveForMatch(match, player, null, destination, MoveReason.EXIT);
            }
        }
    }

    private static boolean isBelowWorld(Location location) {
        return location.getWorld() != null && location.getY() < location.getWorld().getMinHeight();
    }

    private void remindWaitingPlayers() {
        MatchSettings settings = match.getSettings();
        for (MatchPlayer participant : match.getMatchPlayers())
            Feedback.message(participant.getPlayer(), settings.getMessages().getWaitingHint(),
                    "$count", settings.getMinPlayers());
    }
}
