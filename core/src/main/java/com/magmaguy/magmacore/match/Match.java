package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.AttributeManager;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.Block;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.potion.PotionEffect;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Function;

/**
 * Base class for every match. The lifecycle lives here; subclasses add game logic through the
 * protected hooks. Call {@link #open()} once the subclass is fully constructed.
 */
public abstract class Match {
    private final MatchSettings settings;
    private final UUID runtimeId = UUID.randomUUID();
    final List<MatchPlayer> participants = new ArrayList<>();
    MatchPhase phase = MatchPhase.WAITING;
    MatchOutcome outcome;
    private boolean open;
    boolean destroyed;
    // A reusable match removing its participants between runs.
    private boolean resetting;
    final Map<Block, ReviveBanner> reviveBanners = new HashMap<>();
    private final Set<UUID> startingRoster = new LinkedHashSet<>();
    private MatchWatchdog watchdog;
    private BukkitTask countdownTask;
    private BukkitTask destroyTask;

    protected Match(MatchSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        // Fail at construction rather than on the first scheduled task.
        MatchCore.plugin();
    }

    /**
     * Registers the match after asking the plugin API. A vetoed match is destroyed at once: its
     * space is torn down and onDestroy runs, but it never registers and the API never hears of it.
     */
    public final boolean open() {
        if (open || destroyed) return open;
        if (!guard("instantiateAttempt", () -> settings.getApi().instantiateAttempt(this), false)) {
            destroy();
            return false;
        }
        open = true;
        MatchCore.register(this);
        watchdog = MatchWatchdog.start(this);
        return true;
    }

    /**
     * Starts the countdown, or the match itself when the countdown is zero. Needs the minimum
     * number of active players and the plugin API's consent.
     */
    public final StartResult start() {
        if (destroyed || phase != MatchPhase.WAITING) return StartResult.NOT_WAITING;
        if (getActivePlayers().size() < settings.getMinPlayers()) {
            for (MatchPlayer participant : participants)
                Feedback.message(participant.getPlayer(), settings.getMessages().getNotEnoughPlayers(),
                        "$amount", settings.getMinPlayers());
            return StartResult.NOT_ENOUGH_PLAYERS;
        }
        if (!guard("startAttempt", () -> settings.getApi().startAttempt(this), false)) return StartResult.VETOED;
        // The callback runs synchronously and may have ended or started the match itself.
        if (destroyed || phase != MatchPhase.WAITING) return StartResult.NOT_WAITING;
        if (settings.getCountdownSeconds() == 0) {
            beginOngoing();
            return StartResult.STARTED;
        }
        phase = MatchPhase.STARTING;
        countdownTask = Bukkit.getScheduler().runTaskTimer(MatchCore.plugin(), new Countdown(), 0L, 20L);
        return StartResult.STARTED;
    }

    private void beginOngoing() {
        phase = MatchPhase.ONGOING;
        Location start = startDestination();
        for (MatchPlayer participant : List.copyOf(participants))
            if (start != null && participant.entered && participant.role == MatchRole.PLAYER)
                MatchMovement.moveForMatch(this, participant.getPlayer(), participant, start, MoveReason.START);
        startingRoster.clear();
        getActivePlayers().forEach(participant -> startingRoster.add(participant.getUniqueId()));
        try {
            onStart();
        } catch (RuntimeException failure) {
            // A failed start must not leave a half-started match behind.
            Logger.warn("Match " + runtimeId + ": onStart failed, destroying the match: " + failure);
            destroy();
            return;
        }
        guard("started callback", () -> settings.getApi().started(this));
    }

    private void cancelCountdown() {
        if (countdownTask != null) countdownTask.cancel();
        countdownTask = null;
    }

    private final class Countdown implements Runnable {
        private int counter;

        @Override
        public void run() {
            if (destroyed || phase != MatchPhase.STARTING) {
                cancelCountdown();
                return;
            }
            if (getActivePlayers().size() < settings.getMinPlayers()) {
                cancelCountdown();
                requestEnd(MatchOutcome.NEUTRAL);
                return;
            }
            counter++;
            MatchMessages messages = settings.getMessages();
            int remaining = settings.getCountdownSeconds() - counter;
            for (MatchPlayer participant : participants)
                Feedback.title(participant.getPlayer(), messages.getStartingTitle(), messages.getStartingSubtitle(),
                        0, 20, 0, "$count", remaining);
            if (counter >= settings.getCountdownSeconds()) {
                cancelCountdown();
                beginOngoing();
            }
        }
    }

    /** Admits the players together or not at all. Entry teleports happen on the next tick. */
    public final AdmissionResult admit(Collection<? extends Player> players) {
        return admit(players, () -> true);
    }

    /**
     * As {@link #admit(Collection)}, rechecking {@code stillAuthorized} around the cancellable
     * callbacks so an external reservation, such as a party ready check, can withdraw admission.
     */
    public final AdmissionResult admit(Collection<? extends Player> players, BooleanSupplier stillAuthorized) {
        return Admission.admit(this, players, stillAuthorized);
    }

    /** Admits an outsider as a spectator at the start location, in spectator mode. */
    public final AdmissionResult admitSpectator(Player player) {
        return Admission.admitSpectator(this, player);
    }

    /** Moves one of this match's players anywhere inside its space, across its worlds. */
    public final boolean moveParticipant(Player player, Location destination) {
        return MatchMovement.moveForMatch(this, player, destination, MoveReason.MATCH);
    }

    /** An active participant who has entered and is waiting for, or counting down to, the start. */
    final boolean waitingToStart(Player player) {
        MatchPlayer participant = getMatchPlayer(player);
        return (phase == MatchPhase.WAITING || phase == MatchPhase.STARTING)
                && participant != null && participant.role == MatchRole.PLAYER && participant.entered
                && player.isOnline() && !player.isDead() && contains(player.getLocation());
    }

    /**
     * Removes a participant. {@link #onLeave} runs first, while the player is still a participant,
     * so plugins can settle rewards from intact state. Returns false if the player was not in.
     */
    public final boolean leave(Player player, LeaveReason reason) {
        MatchPlayer participant = getMatchPlayer(player);
        if (participant == null) return false;
        participant.leaveReason = reason;
        guard("onLeave", () -> onLeave(participant, reason));
        guard("left callback", () -> settings.getApi().left(this, participant, reason));

        participants.remove(participant);
        MatchCore.untrack(participant);
        MatchMarker.clear(player);
        for (ReviveBanner banner : List.copyOf(reviveBanners.values()))
            if (banner.dead() == participant) banner.clear(false);
        guard("restore " + player.getName(), () -> restore(participant));

        if (!destroyed && !resetting && getActivePlayers().isEmpty()
                && (phase == MatchPhase.WAITING || phase == MatchPhase.STARTING || phase == MatchPhase.ONGOING))
            requestEnd(MatchOutcome.DEFEAT);
        return true;
    }

    private void restore(MatchPlayer participant) {
        Player player = participant.getPlayer();
        guard("custody restore", () -> settings.getCustody().restore(player));
        if (settings.getGameMode() != null || participant.role == MatchRole.SPECTATOR)
            player.setGameMode(participant.getPreviousGameMode());
        // Healing only after the match is over mirrors EliteMobs: a mid-match exit keeps its damage.
        if (phase == MatchPhase.ENDED && player.isOnline() && !player.isDead())
            player.setHealth(maxHealth(player));
        // A player who already walked or teleported out keeps where they are.
        if (player.isOnline() && contains(player.getLocation())) {
            Location exit = null;
            try {
                exit = exitDestination(participant);
            } catch (RuntimeException failure) {
                Logger.warn("Match " + runtimeId + ": exitDestination failed: " + failure);
            }
            if (exit != null) MatchMovement.moveForMatch(this, player, participant, exit, MoveReason.EXIT);
        }
    }

    static double maxHealth(Player player) {
        return AttributeManager.getAttributeValue(player, "generic_max_health");
    }

    /** Ends the match once. Participants stay until destroy, after the configured linger. */
    public final void end(MatchOutcome outcome) {
        if (phase == MatchPhase.ENDED || destroyed) return;
        cancelCountdown();
        this.outcome = outcome;
        phase = MatchPhase.ENDED;
        for (MatchPlayer participant : List.copyOf(participants)) {
            Player player = participant.getPlayer();
            if (player.isOnline() && !player.isDead()) guard("heal", () -> player.setHealth(maxHealth(player)));
        }
        guard("onEnd", () -> onEnd(outcome));
        guard("ended callback", () -> settings.getApi().ended(this, outcome));
        if (!settings.isDestroyAfterEnd()) return;
        // Lingering lets players loot after a win; with nobody alive there is nobody to wait for.
        if (settings.getLingerAfterEndTicks() == 0 || getActivePlayers().isEmpty()) destroy();
        else scheduleDestroy(settings.getLingerAfterEndTicks());
    }

    /** Lethal damage to an active participant of an ongoing match, already cancelled. */
    final void handleLethalDamage(MatchPlayer participant) {
        Player player = participant.getPlayer();
        // The damage was cancelled, so Bukkit's own death cleanup never runs.
        for (PotionEffect effect : player.getActivePotionEffects()) player.removePotionEffect(effect.getType());
        player.setFireTicks(0);
        player.setFreezeTicks(0);
        player.setFallDistance(0);
        guard("onDeath", () -> onDeath(participant));
        if (getMatchPlayer(player) != participant) return;
        boolean handled = guard("death policy", () -> {
            settings.getDeath().handle(this, participant);
            return true;
        }, false);
        if (!handled && getMatchPlayer(player) == participant) leave(player, LeaveReason.DIED);
    }

    /** The spectate-and-revive death policy. The last active player dying ends the match. */
    final void spectateUntilRevived(MatchPlayer participant, Function<MatchPlayer, ReviveMarker> markers) {
        Player player = participant.getPlayer();
        participant.role = MatchRole.SPECTATOR;
        if (getActivePlayers().isEmpty()) {
            leave(player, LeaveReason.DIED);
            return;
        }
        ReviveMarker marker = ReviveMarker.NONE;
        try {
            ReviveMarker supplied = markers.apply(participant);
            if (supplied != null) marker = supplied;
        } catch (RuntimeException failure) {
            Logger.warn("Match " + runtimeId + ": revive marker failed: " + failure);
        }
        new ReviveBanner(this, participant, marker);
        player.setGameMode(GameMode.SPECTATOR);
        guard("onSpectating", () -> onSpectating(participant));
    }

    final void revive(ReviveBanner banner) {
        MatchPlayer dead = banner.dead();
        Player player = dead.getPlayer();
        if (destroyed || phase != MatchPhase.ONGOING || getMatchPlayer(player) != dead) return;
        dead.lives--;
        dead.role = MatchRole.PLAYER;
        player.setGameMode(settings.getGameMode() != null ? settings.getGameMode() : GameMode.SURVIVAL);
        player.setHealth(maxHealth(player));
        MatchMovement.moveForMatch(this, player, dead, banner.respawnLocation(), MoveReason.REVIVE);
        guard("onRevive", () -> onRevive(dead));
    }

    /**
     * Clears the dead player's revive banner, reviving them when {@code revive} is true. Returns
     * false when they have no banner.
     */
    protected final boolean releaseReviveBanner(Player dead, boolean revive) {
        MatchPlayer participant = getMatchPlayer(dead);
        if (participant == null) return false;
        for (ReviveBanner banner : List.copyOf(reviveBanners.values()))
            if (banner.dead() == participant) {
                banner.clear(revive);
                return true;
            }
        return false;
    }

    /** Treats the player as killed: the same handling lethal damage gets. */
    protected final void handleDeath(Player player) {
        MatchPlayer participant = getMatchPlayer(player);
        if (participant == null || participant.role != MatchRole.PLAYER) return;
        if (phase != MatchPhase.ONGOING) {
            leave(player, LeaveReason.DIED);
            return;
        }
        handleLethalDamage(participant);
    }

    /**
     * Every ending the core decides on its own goes through here: defeat when the last active
     * player leaves or dies, a neutral end when a countdown loses its minimum. Subclasses may
     * route, reshape or decline it; the default ends the match.
     */
    protected void requestEnd(MatchOutcome outcome) {
        end(outcome);
    }

    /** Destroys the match after {@code ticks}, replacing any destroy already scheduled. */
    protected final void scheduleDestroy(long ticks) {
        if (destroyed) return;
        if (destroyTask != null) destroyTask.cancel();
        destroyTask = Bukkit.getScheduler().runTaskLater(MatchCore.plugin(), () -> destroy(), ticks);
    }

    /**
     * Removes every participant and tears the space down. Runs once. A reusable match resets to
     * waiting instead and stays registered for its next run; {@link #retire()} destroys it.
     */
    public final void destroy() {
        if (settings.isReusable() && open && !destroyed) {
            reset();
            return;
        }
        destroy(LeaveReason.MATCH_ENDED);
    }

    /** Destroys the match for good, reusable or not. */
    public final void retire() {
        destroy(LeaveReason.MATCH_ENDED);
    }

    private void reset() {
        if (resetting) return;
        resetting = true;
        try {
            cancelCountdown();
            if (destroyTask != null) destroyTask.cancel();
            destroyTask = null;
            for (MatchPlayer participant : List.copyOf(participants))
                guard("remove " + participant.getPlayer().getName(),
                        () -> leave(participant.getPlayer(), LeaveReason.MATCH_ENDED));
            phase = MatchPhase.WAITING;
            outcome = null;
            startingRoster.clear();
            guard("onReset", this::onReset);
            guard("destroyed callback", () -> settings.getApi().destroyed(this));
        } finally {
            resetting = false;
        }
    }

    /**
     * Destroys the match because its plugin or the server is stopping. Participants leave with
     * {@link LeaveReason#SHUTDOWN}, and a reusable match is destroyed for good.
     */
    public final void destroyForShutdown() {
        destroy(LeaveReason.SHUTDOWN);
    }

    private void destroy(LeaveReason reason) {
        if (destroyed) return;
        destroyed = true;
        if (watchdog != null) watchdog.cancel();
        cancelCountdown();
        if (destroyTask != null) destroyTask.cancel();
        if (outcome == null) outcome = MatchOutcome.NEUTRAL;
        for (MatchPlayer participant : List.copyOf(participants))
            guard("remove " + participant.getPlayer().getName(), () -> leave(participant.getPlayer(), reason));
        guard("onDestroy", this::onDestroy);
        if (settings.getSpace() instanceof TemporaryWorlds) guard("evacuation", this::evacuateTemporaryWorlds);
        guard("space teardown", settings.getSpace()::teardown);
        MatchCore.unregister(this);
        phase = MatchPhase.DESTROYED;
        // The API only hears about matches it saw open.
        if (open) guard("destroyed callback", () -> settings.getApi().destroyed(this));
    }

    // A world cannot unload with anyone inside, such as staff holding the bypass permission.
    private void evacuateTemporaryWorlds() {
        for (World world : settings.getSpace().worlds())
            for (Player player : List.copyOf(world.getPlayers())) {
                Location destination = intruderDestination(player);
                if (destination != null) player.teleport(destination, PlayerTeleportEvent.TeleportCause.PLUGIN);
            }
    }

    public UUID getRuntimeId() {
        return runtimeId;
    }

    public final MatchPhase getPhase() {
        return phase;
    }

    public final MatchOutcome getOutcome() {
        return outcome;
    }

    public final MatchSettings getSettings() {
        return settings;
    }

    /** The active players when the match went ongoing. */
    public final Set<UUID> getStartingRoster() {
        return Set.copyOf(startingRoster);
    }

    public final boolean isOpen() {
        return open && !destroyed;
    }

    public final boolean isDestroyed() {
        return destroyed;
    }

    public final boolean contains(Location location) {
        return settings.getSpace().contains(location);
    }

    /** This match's participant for the player, or null. */
    public final MatchPlayer getMatchPlayer(Player player) {
        for (MatchPlayer participant : participants)
            if (participant.getPlayer().equals(player)) return participant;
        return null;
    }

    public final List<MatchPlayer> getMatchPlayers() {
        return List.copyOf(participants);
    }

    public final List<MatchPlayer> getActivePlayers() {
        return participants.stream().filter(participant -> participant.role == MatchRole.PLAYER).toList();
    }

    protected MatchPlayer createPlayer(Player player) {
        return new MatchPlayer(player, this);
    }

    protected Location entryDestination(MatchPlayer player) {
        if (phase == MatchPhase.WAITING && settings.getLobby() != null) return settings.getLobby();
        return startDestination();
    }

    /** Where an outsider who joins as a spectator is placed. */
    protected Location spectatorDestination(MatchPlayer player) {
        return startDestination();
    }

    /** Where players go when the match starts, and the rescue point of last resort. */
    protected Location startDestination() {
        return settings.getStart();
    }

    /** Settings exit, else where the player came from, else the main world spawn. */
    protected Location exitDestination(MatchPlayer player) {
        if (settings.getExit() != null) return settings.getExit();
        Location previous = player.getPreviousLocation();
        World world = previous.getWorld();
        if (world != null && Bukkit.getWorld(world.getUID()) == world) return previous;
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    /** Where non-participants found inside a running match are sent. */
    protected Location intruderDestination(Player player) {
        if (settings.getExit() != null) return settings.getExit();
        return Bukkit.getWorlds().getFirst().getSpawnLocation();
    }

    /** Whether an outsider may join as a spectator. Players who die become spectators regardless. */
    protected boolean acceptsSpectator(Player player) {
        return settings.isSpectatable();
    }

    /** Taken after every admission check passes, before anyone is registered. */
    protected boolean reserveAdmission() {
        return true;
    }

    /** Releases a reservation when admission could not finish. */
    protected void abortAdmission() {
    }

    protected void onJoin(MatchPlayer player) {
    }

    protected void onStart() {
    }

    protected void onDeath(MatchPlayer player) {
    }

    /** Runs while the player is still a participant and the match state is intact. */
    protected void onLeave(MatchPlayer player, LeaveReason reason) {
    }

    protected void onEnd(MatchOutcome outcome) {
    }

    /**
     * Runs once, after every participant has left and before the space is torn down, so what
     * the plugin placed inside temporary worlds can still be removed from them.
     */
    protected void onDestroy() {
    }

    /** The permission players need to join; spectators do not need it. */
    protected String requiredPermission() {
        return settings.getPermission();
    }

    /** Admission failed after this player was created; undo anything createPlayer recorded. */
    protected void onAdmissionRolledBack(MatchPlayer player) {
    }

    /** Whether the match takes players right now, on top of the core's own checks. */
    protected boolean acceptsPlayers() {
        return true;
    }

    /** The death policy turned an active player into a spectator. */
    protected void onSpectating(MatchPlayer player) {
    }

    /** A spectating player was revived and is active again. */
    protected void onRevive(MatchPlayer player) {
    }

    /** A reusable match finished a run and is waiting again. */
    protected void onReset() {
    }

    /** Runs a plugin or add-on callback so one failure cannot break the lifecycle. */
    final boolean guard(String what, BooleanSupplier call, boolean fallback) {
        try {
            return call.getAsBoolean();
        } catch (RuntimeException failure) {
            Logger.warn("Match " + runtimeId + ": " + what + " failed: " + failure);
            return fallback;
        }
    }

    final void guard(String what, Runnable call) {
        guard(what, () -> {
            call.run();
            return true;
        }, true);
    }
}
