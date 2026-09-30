package com.magmaguy.magmacore.match;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
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

        List<MatchPlayer> registered = new ArrayList<>();
        List<BukkitTask> entries = new ArrayList<>();
        try {
            for (Player player : group) registered.add(register(match, player));
            for (MatchPlayer participant : registered) entries.add(scheduleEntry(match, participant));
        } catch (RuntimeException failure) {
            entries.forEach(BukkitTask::cancel);
            rollback(match, registered);
            Logger.warn("Match " + match.getRuntimeId() + ": admission failed: " + failure);
            return AdmissionResult.FAILED;
        }

        registered.forEach(participant -> announce(match, participant));
        return AdmissionResult.ADMITTED;
    }

    private static AdmissionResult check(Match match, List<Player> group) {
        MatchSettings settings = match.getSettings();
        if (!match.isOpen() || match.phase != MatchPhase.WAITING) return AdmissionResult.NOT_ACCEPTING;
        if (match.getActivePlayers().size() + group.size() > settings.getMaxPlayers()) return AdmissionResult.FULL;
        for (Player player : group) {
            if (!player.isOnline() || !player.isValid()) return AdmissionResult.UNAVAILABLE;
            // Either index disagreeing counts as occupied rather than letting one player join two matches.
            if (MatchMarker.occupied(player) || MatchCore.participant(player) != null)
                return AdmissionResult.ALREADY_IN_MATCH;
            if (settings.getPermission() != null && !player.hasPermission(settings.getPermission()))
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
        if (settings.getGameMode() != null) player.setGameMode(settings.getGameMode());
        return participant;
    }

    static void rollback(Match match, List<MatchPlayer> registered) {
        for (MatchPlayer participant : registered) {
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
