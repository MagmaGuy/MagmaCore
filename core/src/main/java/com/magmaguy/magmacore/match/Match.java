package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import java.util.function.BooleanSupplier;

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

    protected Match(MatchSettings settings) {
        this.settings = Objects.requireNonNull(settings, "settings");
        // Fail at construction rather than on the first scheduled task.
        MatchCore.plugin();
    }

    /**
     * Registers the match after asking the plugin API. A vetoed match is destroyed without
     * ever registering or firing the destroyed callback.
     */
    public final boolean open() {
        if (open || destroyed) return open;
        if (!guard("instantiateAttempt", () -> settings.getApi().instantiateAttempt(this), false)) {
            destroyed = true;
            phase = MatchPhase.DESTROYED;
            return false;
        }
        open = true;
        MatchCore.register(this);
        return true;
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

    public final boolean leave(Player player, LeaveReason reason) {
        MatchPlayer participant = getMatchPlayer(player);
        return participant != null;
    }

    /** Removes every participant and tears the space down. Runs once. */
    public final void destroy() {
        destroy(LeaveReason.MATCH_ENDED);
    }

    final void shutdownDestroy() {
        destroy(LeaveReason.SHUTDOWN);
    }

    private void destroy(LeaveReason reason) {
        if (destroyed) return;
        destroyed = true;
        if (outcome == null) outcome = MatchOutcome.NEUTRAL;
        guard("space teardown", settings.getSpace()::teardown);
        MatchCore.unregister(this);
        phase = MatchPhase.DESTROYED;
        guard("onDestroy", this::onDestroy);
        guard("destroyed callback", () -> settings.getApi().destroyed(this));
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
        return settings.getStart();
    }

    protected Location exitDestination(MatchPlayer player) {
        return settings.getExit();
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

    protected void onDestroy() {
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
