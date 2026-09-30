package com.magmaguy.magmacore.match;

import lombok.Getter;
import org.bukkit.GameMode;
import org.bukkit.Location;

import java.util.Objects;

/** Everything a match is configured with, fixed at construction. */
@Getter
public final class MatchSettings {
    private final int minPlayers;
    private final int maxPlayers;
    private final Location lobby;
    private final Location start;
    private final Location exit;
    private final String permission;
    /** Zero starts the match as soon as players are admitted. */
    private final int countdownSeconds;
    /** Null leaves the player's game mode unchanged on entry. */
    private final GameMode gameMode;
    private final boolean spectatable;
    /** Holders may teleport into the space and are never ejected as intruders. Null: nobody. */
    private final String bypassPermission;
    /** Holders may use command and plugin teleports inside an ongoing match. Null: nobody. */
    private final String withinTeleportPermission;
    private final long lingerAfterEndTicks;
    /** When false the subclass destroys the match itself after it ends. */
    private final boolean destroyAfterEnd;
    /** Region matches that reset to waiting between runs instead of being destroyed. */
    private final boolean reusable;
    private final MatchSpace space;
    private final DeathPolicy death;
    private final PlayerCustody custody;
    private final MatchMessages messages;
    private final MatchApi api;

    private MatchSettings(Builder builder) {
        this.minPlayers = builder.minPlayers;
        this.maxPlayers = builder.maxPlayers;
        this.lobby = clone(builder.lobby);
        this.start = clone(builder.start);
        this.exit = clone(builder.exit);
        this.permission = builder.permission;
        this.countdownSeconds = builder.countdownSeconds;
        this.gameMode = builder.gameMode;
        this.spectatable = builder.spectatable;
        this.bypassPermission = builder.bypassPermission;
        this.withinTeleportPermission = builder.withinTeleportPermission;
        this.lingerAfterEndTicks = builder.lingerAfterEndTicks;
        this.destroyAfterEnd = builder.destroyAfterEnd;
        this.reusable = builder.reusable;
        this.space = builder.space;
        this.death = builder.death;
        this.custody = builder.custody;
        this.messages = builder.messages;
        this.api = builder.api;
    }

    public static Builder builder() {
        return new Builder();
    }

    public Location getLobby() {
        return clone(lobby);
    }

    public Location getStart() {
        return clone(start);
    }

    public Location getExit() {
        return clone(exit);
    }

    private static Location clone(Location location) {
        return location == null ? null : location.clone();
    }

    public static final class Builder {
        private int minPlayers = 1;
        private int maxPlayers = 1;
        private Location lobby;
        private Location start;
        private Location exit;
        private String permission;
        private int countdownSeconds = 3;
        private GameMode gameMode;
        private boolean spectatable;
        private String bypassPermission;
        private String withinTeleportPermission;
        private long lingerAfterEndTicks;
        private boolean destroyAfterEnd = true;
        private boolean reusable;
        private MatchSpace space;
        private DeathPolicy death = DeathPolicy.eliminate();
        private PlayerCustody custody = PlayerCustody.none();
        private MatchMessages messages = MatchMessages.defaults();
        private MatchApi api = MatchApi.NONE;

        private Builder() {
        }

        public Builder players(int min, int max) {
            if (min < 0 || max < 1 || min > max)
                throw new IllegalArgumentException("Invalid player range " + min + ".." + max);
            this.minPlayers = min;
            this.maxPlayers = max;
            return this;
        }

        public Builder lobby(Location lobby) {
            this.lobby = lobby;
            return this;
        }

        public Builder start(Location start) {
            this.start = start;
            return this;
        }

        public Builder exit(Location exit) {
            this.exit = exit;
            return this;
        }

        public Builder permission(String permission) {
            this.permission = permission;
            return this;
        }

        public Builder countdownSeconds(int countdownSeconds) {
            if (countdownSeconds < 0) throw new IllegalArgumentException("Negative countdown");
            this.countdownSeconds = countdownSeconds;
            return this;
        }

        public Builder gameMode(GameMode gameMode) {
            this.gameMode = gameMode;
            return this;
        }

        public Builder spectatable(boolean spectatable) {
            this.spectatable = spectatable;
            return this;
        }

        public Builder bypassPermission(String bypassPermission) {
            this.bypassPermission = bypassPermission;
            return this;
        }

        public Builder withinTeleportPermission(String withinTeleportPermission) {
            this.withinTeleportPermission = withinTeleportPermission;
            return this;
        }

        public Builder lingerAfterEndTicks(long lingerAfterEndTicks) {
            if (lingerAfterEndTicks < 0) throw new IllegalArgumentException("Negative linger");
            this.lingerAfterEndTicks = lingerAfterEndTicks;
            return this;
        }

        /** False leaves destroying an ended match to the subclass, which then ignores the linger. */
        public Builder destroyAfterEnd(boolean destroyAfterEnd) {
            this.destroyAfterEnd = destroyAfterEnd;
            return this;
        }

        /** The match resets to waiting after each run, as an arena does. Not for temporary worlds. */
        public Builder reusable(boolean reusable) {
            this.reusable = reusable;
            return this;
        }

        public Builder space(MatchSpace space) {
            this.space = space;
            return this;
        }

        public Builder death(DeathPolicy death) {
            this.death = Objects.requireNonNull(death, "death");
            return this;
        }

        public Builder custody(PlayerCustody custody) {
            this.custody = Objects.requireNonNull(custody, "custody");
            return this;
        }

        public Builder messages(MatchMessages messages) {
            this.messages = Objects.requireNonNull(messages, "messages");
            return this;
        }

        public Builder api(MatchApi api) {
            this.api = Objects.requireNonNull(api, "api");
            return this;
        }

        public MatchSettings build() {
            Objects.requireNonNull(space, "A match needs a space");
            // A temporary world is deleted when its match ends, so there is nothing to reuse.
            if (reusable && space instanceof TemporaryWorlds)
                throw new IllegalArgumentException("Temporary-world matches cannot be reusable");
            return new MatchSettings(this);
        }
    }
}
