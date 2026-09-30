package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.GameMode;
import org.bukkit.Location;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;
import java.util.function.BooleanSupplier;

/**
 * All-or-nothing admission, ported from EliteMobs' InstancePlayerManager.addNewPlayers. Every
 * check and every cancellable API callback runs for the whole group before any state changes,
 * so a group is never split by a full match, a missing permission, another match or a veto.
 */
final class Admission {
    private Admission() {
    }

    static AdmissionResult admit(Match match, Collection<? extends Player> requested, BooleanSupplier stillAuthorized) {
        if (stillAuthorized == null || !stillAuthorized.getAsBoolean()) return AdmissionResult.NOT_AUTHORIZED;
        LinkedHashMap<UUID, Player> unique = new LinkedHashMap<>();
        for (Player player : requested)
            if (player != null) unique.putIfAbsent(player.getUniqueId(), player);
        if (unique.isEmpty()) return AdmissionResult.UNAVAILABLE;
        List<Player> group = unique.values().stream()
                .filter(player -> match.getMatchPlayer(player) == null)
                .toList();
        if (group.isEmpty()) return AdmissionResult.ALREADY_IN_MATCH;

        AdmissionResult check = check(match, group);
        if (check != AdmissionResult.ADMITTED) return refuse(match, group, check);
        MatchApi api = match.getSettings().getApi();
        for (Player player : group) {
            if (!match.guard("joinAttempt", () -> api.joinAttempt(match, player), false)) return AdmissionResult.VETOED;
            if (!stillAuthorized.getAsBoolean()) return AdmissionResult.NOT_AUTHORIZED;
        }
        // Join callbacks run synchronously and can change match or player state. Fail closed
        // if anything changed during the preflight instead of admitting part of a group.
        if (!stillAuthorized.getAsBoolean()) return AdmissionResult.NOT_AUTHORIZED;
        check = check(match, group);
        if (check != AdmissionResult.ADMITTED) return refuse(match, group, check);
        if (!match.guard("reserveAdmission", match::reserveAdmission, false)) return AdmissionResult.NOT_ACCEPTING;

        MatchSettings settings = match.getSettings();
        PlayerCustody custody = settings.getCustody();
        List<MatchPlayer> registered = new ArrayList<>();
        List<MatchPlayer> captured = new ArrayList<>();
        List<BukkitTask> entries = new ArrayList<>();
        try {
            for (Player player : group) registered.add(register(match, player));
            // Custody must see the player exactly as they were, so it runs before any change.
            for (MatchPlayer participant : registered) {
                custody.capture(participant.getPlayer());
                captured.add(participant);
            }
            if (settings.getGameMode() != null)
                registered.forEach(participant -> participant.getPlayer().setGameMode(settings.getGameMode()));
            for (MatchPlayer participant : registered) entries.add(scheduleEntry(match, participant));
        } catch (RuntimeException failure) {
            entries.forEach(BukkitTask::cancel);
            for (MatchPlayer participant : captured)
                match.guard("custody release", () -> custody.restore(participant.getPlayer()));
            rollback(match, registered);
            Logger.warn("Match " + match.getRuntimeId() + ": admission failed: " + failure);
            return AdmissionResult.FAILED;
        }

        registered.forEach(participant -> announce(match, participant));
        if (settings.getCountdownSeconds() == 0 && match.phase == MatchPhase.WAITING
                && match.getActivePlayers().size() >= settings.getMinPlayers())
            match.start();
        return AdmissionResult.ADMITTED;
    }

    /**
     * Admits an outsider as a spectator at the start location. Ported from EliteMobs'
     * addSpectator for players who were not in the match.
     */
    static AdmissionResult admitSpectator(Match match, Player player) {
        AdmissionResult check = checkSpectator(match, player);
        if (check != AdmissionResult.ADMITTED) return check;
        MatchSettings settings = match.getSettings();
        if (!match.guard("joinAttempt", () -> settings.getApi().joinAttempt(match, player), false))
            return AdmissionResult.VETOED;
        check = checkSpectator(match, player);
        if (check != AdmissionResult.ADMITTED) return check;
        if (!match.guard("reserveAdmission", match::reserveAdmission, false)) return AdmissionResult.NOT_ACCEPTING;

        List<MatchPlayer> registered = new ArrayList<>();
        boolean captured = false;
        boolean admitted = false;
        try {
            MatchPlayer participant = register(match, player);
            registered.add(participant);
            participant.role = MatchRole.SPECTATOR;
            participant.lives = 0;
            settings.getCustody().capture(player);
            captured = true;
            player.setGameMode(GameMode.SPECTATOR);
            Location start = match.startDestination();
            admitted = start != null && MatchMovement.moveForMatch(match, player, participant, start, MoveReason.ENTRY);
            participant.entered = admitted;
        } catch (RuntimeException failure) {
            Logger.warn("Match " + match.getRuntimeId() + ": spectator admission failed: " + failure);
        } finally {
            if (!admitted) {
                if (captured) match.guard("custody release", () -> settings.getCustody().restore(player));
                if (!registered.isEmpty()) player.setGameMode(registered.getFirst().getPreviousGameMode());
                rollback(match, registered);
            }
        }
        if (!admitted) return AdmissionResult.FAILED;

        MatchPlayer participant = registered.getFirst();
        MatchMessages messages = settings.getMessages();
        match.guard("spectator feedback", () -> {
            Feedback.message(player, messages.getSpectatorMessage());
            Feedback.title(player, messages.getSpectatorTitle(), messages.getSpectatorSubtitle(), 60, 180, 60);
        });
        match.guard("onJoin", () -> match.onJoin(participant));
        match.guard("joined callback", () -> settings.getApi().joined(match, participant));
        return AdmissionResult.ADMITTED;
    }

    private static AdmissionResult checkSpectator(Match match, Player player) {
        if (player == null || !player.isOnline() || !player.isValid()) return AdmissionResult.UNAVAILABLE;
        if (!match.isOpen()) return AdmissionResult.NOT_ACCEPTING;
        if (MatchMarker.occupied(player) || MatchCore.participant(player) != null)
            return AdmissionResult.ALREADY_IN_MATCH;
        if (!match.guard("acceptsSpectator", () -> match.acceptsSpectator(player), false))
            return AdmissionResult.NOT_ACCEPTING;
        return AdmissionResult.ADMITTED;
    }

    private static AdmissionResult check(Match match, List<Player> group) {
        MatchSettings settings = match.getSettings();
        if (!match.isOpen() || match.phase != MatchPhase.WAITING
                || !match.guard("acceptsPlayers", match::acceptsPlayers, false)) return AdmissionResult.NOT_ACCEPTING;
        if (match.getActivePlayers().size() + group.size() > settings.getMaxPlayers()) return AdmissionResult.FULL;
        for (Player player : group) {
            if (!player.isOnline() || !player.isValid()) return AdmissionResult.UNAVAILABLE;
            // Either index disagreeing counts as occupied rather than letting one player join two matches.
            if (MatchMarker.occupied(player) || MatchCore.participant(player) != null)
                return AdmissionResult.ALREADY_IN_MATCH;
            String permission = match.requiredPermission();
            if (permission != null && !player.hasPermission(permission))
                return AdmissionResult.NO_PERMISSION;
        }
        return AdmissionResult.ADMITTED;
    }

    private static AdmissionResult refuse(Match match, List<Player> group, AdmissionResult result) {
        MatchMessages messages = match.getSettings().getMessages();
        String template = switch (result) {
            case NOT_ACCEPTING -> messages.getNotAccepting();
            case FULL -> messages.getFull();
            case NO_PERMISSION -> messages.getNoPermission();
            case ALREADY_IN_MATCH -> messages.getAlreadyInMatch();
            default -> null;
        };
        Feedback.message(group.getFirst(), template);
        return result;
    }

    private static MatchPlayer register(Match match, Player player) {
        MatchSettings settings = match.getSettings();
        MatchPlayer participant = match.createPlayer(player);
        participant.lives = settings.getDeath().initialLives();
        match.participants.add(participant);
        MatchCore.track(participant);
        MatchMarker.set(player, match);
        return participant;
    }

    static void rollback(Match match, List<MatchPlayer> registered) {
        for (MatchPlayer participant : registered) {
            match.guard("onAdmissionRolledBack", () -> match.onAdmissionRolledBack(participant));
            match.participants.remove(participant);
            MatchCore.untrack(participant);
            MatchMarker.clear(participant.getPlayer());
            if (match.getSettings().getGameMode() != null)
                participant.getPlayer().setGameMode(participant.getPreviousGameMode());
        }
        match.guard("abortAdmission", match::abortAdmission);
    }

    private static BukkitTask scheduleEntry(Match match, MatchPlayer participant) {
        return Bukkit.getScheduler().runTaskLater(MatchCore.plugin(), () -> {
            Player player = participant.getPlayer();
            if (!player.isOnline() || MatchCore.participant(player) != participant || match.isDestroyed()) return;
            Location destination = null;
            try {
                destination = match.entryDestination(participant);
            } catch (RuntimeException failure) {
                Logger.warn("Match " + match.getRuntimeId() + ": entryDestination failed: " + failure);
            }
            if (destination == null || !MatchMovement.moveForMatch(match, player, destination, MoveReason.ENTRY)) {
                match.leave(player, LeaveReason.QUIT);
                return;
            }
            participant.entered = true;
        }, 1L);
    }

    private static void announce(Match match, MatchPlayer participant) {
        MatchSettings settings = match.getSettings();
        MatchMessages messages = settings.getMessages();
        Player player = participant.getPlayer();
        match.guard("join feedback", () -> {
            Feedback.message(player, messages.getJoinedMessage(), "$count", settings.getMinPlayers());
            Feedback.title(player, messages.getJoinedTitle(), messages.getJoinedSubtitle(), 60, 180, 60);
        });
        match.guard("onJoin", () -> match.onJoin(participant));
        match.guard("joined callback", () -> settings.getApi().joined(match, participant));
    }
}
