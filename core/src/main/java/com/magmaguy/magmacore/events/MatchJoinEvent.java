package com.magmaguy.magmacore.events;

import com.magmaguy.magmacore.instance.MatchEvent;
import com.magmaguy.magmacore.instance.MatchInstance;
import lombok.Getter;
import org.bukkit.entity.Player;
import org.bukkit.event.Cancellable;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;

public class MatchJoinEvent extends Event implements MatchEvent, Cancellable {
    private static final HandlerList handlers = new HandlerList();
    private final MatchInstance matchInstance;
    @Getter
    private final Player player;
    private boolean cancelled = false;

    public MatchJoinEvent(MatchInstance matchInstance, Player player) {
        this.matchInstance = matchInstance;
        this.player = player;
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
    public boolean isCancelled() {
        return cancelled;
    }

    @Override
    public void setCancelled(boolean b) {
        this.cancelled = b;
    }

}
