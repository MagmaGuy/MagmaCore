package com.magmaguy.magmacore.util;

import org.apache.logging.log4j.Level;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Marker;
import org.apache.logging.log4j.core.LoggerContext;
import org.apache.logging.log4j.core.config.Configuration;
import org.apache.logging.log4j.core.filter.AbstractFilter;
import org.apache.logging.log4j.message.Message;
import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Predicate;

/**
 * Hides vanilla's "Named entity ... died" console line for entities a plugin owns.
 * LivingEntity#die logs that line for every mob with a custom name, so a plugin that names its mobs
 * floods the console. Other named entities, such as name-tagged pets, still log.
 * <p>
 * The filter sits on the server's log4j configuration, which log4j consults before it builds a log event.
 * There it sees vanilla's format string and the dying NMS entity, on the server thread, while the entity
 * still carries its plugin tags. Log4j types stay inside {@link DeathLineFilter} so a server without
 * log4j-core only loses the filter.
 */
public final class NamedDeathLogFilter {
    // LivingEntity#die: LOGGER.info("Named entity {} died: {}", this, deathMessage)
    private static final String NAMED_DEATH_FORMAT = "Named entity {} died: {}";
    private static final List<Predicate<Entity>> ownedEntityChecks = new CopyOnWriteArrayList<>();
    private static Runnable uninstall = null;

    private NamedDeathLogFilter() {
    }

    /** Hides the named-death line for every entity that {@code isOwned} accepts. Server thread only. */
    public static void register(Predicate<Entity> isOwned) {
        ownedEntityChecks.add(isOwned);
        if (uninstall != null) return;
        try {
            uninstall = DeathLineFilter.install();
        } catch (LinkageError | RuntimeException e) {
            Logger.warn("Could not filter named mob deaths from the console: " + e);
        }
    }

    /** Removes the filter. A filter left in log4j would keep this plugin's classes loaded after a restart-free update. */
    public static void shutdown() {
        ownedEntityChecks.clear();
        if (uninstall == null) return;
        try {
            uninstall.run();
        } catch (LinkageError | RuntimeException e) {
            Logger.warn("Could not remove the named mob death console filter: " + e);
        }
        uninstall = null;
    }

    private static boolean isOwnedDeathLine(String format, Object nmsEntity) {
        if (!NAMED_DEATH_FORMAT.equals(format) || nmsEntity == null || !Bukkit.isPrimaryThread()) return false;
        try {
            // CraftBukkit adds getBukkitEntity to the NMS entity; the name survives Spigot's obfuscation.
            Object bukkitEntity = nmsEntity.getClass().getMethod("getBukkitEntity").invoke(nmsEntity);
            if (!(bukkitEntity instanceof Entity entity)) return false;
            for (Predicate<Entity> check : ownedEntityChecks)
                if (check.test(entity)) return true;
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // A failed check must never break logging; the line just prints.
        }
        return false;
    }

    private static final class DeathLineFilter extends AbstractFilter {
        private static Runnable install() {
            // The server's context, not one log4j might create for this plugin's class loader.
            LoggerContext context = (LoggerContext) LogManager.getContext(Bukkit.class.getClassLoader(), false);
            Configuration configuration = context.getConfiguration();
            DeathLineFilter filter = new DeathLineFilter();
            filter.start();
            configuration.addFilter(filter);
            return () -> {
                configuration.removeFilter(filter);
                filter.stop();
            };
        }

        // Named in full: com.magmaguy.magmacore.util.Logger shares this package.
        @Override
        public Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker, String msg,
                             Object p0, Object p1) {
            return isOwnedDeathLine(msg, p0) ? Result.DENY : Result.NEUTRAL;
        }

        @Override
        public Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker, String msg,
                             Object... params) {
            return params != null && params.length == 2 && isOwnedDeathLine(msg, params[0]) ? Result.DENY : Result.NEUTRAL;
        }

        @Override
        public Result filter(org.apache.logging.log4j.core.Logger logger, Level level, Marker marker, Message msg,
                             Throwable t) {
            if (msg == null) return Result.NEUTRAL;
            Object[] params = msg.getParameters();
            return params != null && params.length == 2 && isOwnedDeathLine(msg.getFormat(), params[0])
                    ? Result.DENY : Result.NEUTRAL;
        }
    }
}
