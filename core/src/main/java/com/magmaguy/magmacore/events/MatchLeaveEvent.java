package com.magmaguy.magmacore.events;

import com.magmaguy.magmacore.instance.MatchEvent;
import com.magmaguy.magmacore.instance.MatchInstance;
import com.magmaguy.magmacore.instance.MatchPlayer;
import com.magmaguy.magmacore.instance.MatchPlayerEvent;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class MatchLeaveEvent extends Event implements MatchEvent, MatchPlayerEvent {
    private static final HandlerList handlers = new HandlerList();
    private final MatchInstance matchInstance;
    private final MatchPlayer matchPlayer;

    public MatchLeaveEvent(MatchInstance matchInstance, MatchPlayer player) {
        this.matchInstance = matchInstance;
        this.matchPlayer = player;
    }

    @Override
    public MatchInstance getInstance() {
        return matchInstance;
    }

    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    @Override
    public MatchPlayer getMatchPlayer() {
        return matchPlayer;
    }
}