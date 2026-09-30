package com.magmaguy.magmacore.match;

import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/** Records hook calls as "hook:detail" strings. Fields let tests steer hooks. */
class TestMatch extends Match {
    final List<String> hooks = new ArrayList<>();
    Function<MatchPlayer, Location> entry;
    Function<MatchPlayer, Location> exit;
    boolean refuseReservation;
    RuntimeException throwOnStart;
    RuntimeException throwOnLeave;
    Boolean participantDuringOnLeave;
    boolean declineAutomaticEnds;

    TestMatch(MatchSettings settings) {
        super(settings);
    }

    @Override
    protected Location entryDestination(MatchPlayer player) {
        return entry == null ? super.entryDestination(player) : entry.apply(player);
    }

    @Override
    protected Location exitDestination(MatchPlayer player) {
        return exit == null ? super.exitDestination(player) : exit.apply(player);
    }

    @Override
    protected boolean reserveAdmission() {
        hooks.add("reserveAdmission");
        return !refuseReservation;
    }

    @Override
    protected void abortAdmission() {
        hooks.add("abortAdmission");
    }

    @Override
    protected void onJoin(MatchPlayer player) {
        hooks.add("onJoin:" + player.getPlayer().getName());
    }

    @Override
    protected void onStart() {
        hooks.add("onStart");
        if (throwOnStart != null) throw throwOnStart;
    }

    @Override
    protected void onDeath(MatchPlayer player) {
        hooks.add("onDeath:" + player.getPlayer().getName());
    }

    @Override
    protected void onLeave(MatchPlayer player, LeaveReason reason) {
        hooks.add("onLeave:" + player.getPlayer().getName() + ":" + reason);
        participantDuringOnLeave = getMatchPlayer(player.getPlayer()) != null;
        if (throwOnLeave != null) throw throwOnLeave;
    }

    @Override
    protected void onEnd(MatchOutcome outcome) {
        hooks.add("onEnd:" + outcome);
    }

    @Override
    protected void onDestroy() {
        hooks.add("onDestroy");
    }

    @Override
    protected void onReset() {
        hooks.add("onReset");
    }

    @Override
    protected void requestEnd(MatchOutcome outcome) {
        hooks.add("requestEnd:" + outcome);
        if (!declineAutomaticEnds) super.requestEnd(outcome);
    }

    MatchPlayer participant(Player player) {
        return getMatchPlayer(player);
    }
}
