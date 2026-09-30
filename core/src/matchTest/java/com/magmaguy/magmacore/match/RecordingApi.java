package com.magmaguy.magmacore.match;

import org.bukkit.entity.Player;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

class RecordingApi implements MatchApi {
    final List<String> events = new ArrayList<>();
    final Set<String> vetoJoin = new HashSet<>();
    boolean vetoInstantiate;
    boolean vetoStart;
    Runnable duringJoinAttempt;

    @Override
    public boolean instantiateAttempt(Match match) {
        events.add("instantiateAttempt");
        return !vetoInstantiate;
    }

    @Override
    public boolean joinAttempt(Match match, Player player) {
        events.add("joinAttempt:" + player.getName());
        if (duringJoinAttempt != null) duringJoinAttempt.run();
        return !vetoJoin.contains(player.getName());
    }

    @Override
    public void joined(Match match, MatchPlayer player) {
        events.add("joined:" + player.getPlayer().getName());
    }

    @Override
    public boolean startAttempt(Match match) {
        events.add("startAttempt");
        return !vetoStart;
    }

    @Override
    public void started(Match match) {
        events.add("started");
    }

    @Override
    public void left(Match match, MatchPlayer player, LeaveReason reason) {
        events.add("left:" + player.getPlayer().getName() + ":" + reason);
    }

    @Override
    public void ended(Match match, MatchOutcome outcome) {
        events.add("ended:" + outcome);
    }

    @Override
    public void destroyed(Match match) {
        events.add("destroyed");
    }
}
