package com.magmaguy.magmacore.scripting.tables;

import com.magmaguy.magmacore.MagmaCore;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.TextComponent;
import org.bukkit.Bukkit;
import org.bukkit.boss.BarColor;
import org.bukkit.boss.BarStyle;
import org.bukkit.boss.BossBar;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitTask;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

public class LuaPlayerUITable {

    private static final Map<UUID, BossBar> activeBossBars = new ConcurrentHashMap<>();
    private static final Map<UUID, BukkitTask> activeActionBars = new ConcurrentHashMap<>();

    public static void addTo(LuaTable table, Player player) {
        UUID uuid = player.getUniqueId();

        table.set("show_boss_bar", LuaTableSupport.tableMethod(table, args -> {
            String text = args.checkjstring(1);
            String colorName = args.optjstring(2, "WHITE");
            double progress = args.checkdouble(3);
            int ticks = args.optint(4, -1);

            BarColor barColor;
            try {
                barColor = BarColor.valueOf(colorName.toUpperCase());
            } catch (IllegalArgumentException e) {
                barColor = BarColor.WHITE;
            }

            BarColor finalBarColor = barColor;
            Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                BossBar existing = activeBossBars.remove(uuid);
                if (existing != null) existing.removeAll();

                BossBar bar = Bukkit.createBossBar(text, finalBarColor, BarStyle.SOLID);
                bar.setProgress(Math.max(0.0, Math.min(1.0, progress)));
                bar.addPlayer(player);
                activeBossBars.put(uuid, bar);

                if (ticks > 0) {
                    Bukkit.getScheduler().runTaskLater(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                        BossBar current = activeBossBars.remove(uuid);
                        if (current != null) current.removeAll();
                    }, ticks);
                }
            });
            return LuaValue.NIL;
        }));

        table.set("hide_boss_bar", LuaTableSupport.tableMethod(table, args -> {
            Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                BossBar bar = activeBossBars.remove(uuid);
                if (bar != null) bar.removeAll();
            });
            return LuaValue.NIL;
        }));

        table.set("show_action_bar", LuaTableSupport.tableMethod(table, args -> {
            String text = args.checkjstring(1);
            int ticks = args.optint(2, -1);

            // Cancel any existing action bar task
            BukkitTask existing = activeActionBars.remove(uuid);
            if (existing != null) existing.cancel();

            if (ticks <= 0) {
                // One-shot
                Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () ->
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text)));
            } else {
                // Repeating: re-send every 40 ticks, auto-stop after total ticks
                BukkitTask task = Bukkit.getScheduler().runTaskTimer(MagmaCore.getInstance().getRequestingPlugin(), () ->
                        player.spigot().sendMessage(ChatMessageType.ACTION_BAR, new TextComponent(text)), 0L, 40L);
                activeActionBars.put(uuid, task);

                Bukkit.getScheduler().runTaskLater(MagmaCore.getInstance().getRequestingPlugin(), () -> {
                    BukkitTask t = activeActionBars.remove(uuid);
                    if (t != null) t.cancel();
                }, ticks);
            }
            return LuaValue.NIL;
        }));

        table.set("show_title", LuaTableSupport.tableMethod(table, args -> {
            String title = args.checkjstring(1);
            String subtitle = args.optjstring(2, "");
            int fadeIn = args.checkint(3);
            int stay = args.checkint(4);
            int fadeOut = args.checkint(5);

            Bukkit.getScheduler().runTask(MagmaCore.getInstance().getRequestingPlugin(), () ->
                    player.sendTitle(title, subtitle, fadeIn, stay, fadeOut));
            return LuaValue.NIL;
        }));
    }

    public static void cleanup(UUID uuid) {
        BossBar bar = activeBossBars.remove(uuid);
        if (bar != null) bar.removeAll();

        BukkitTask task = activeActionBars.remove(uuid);
        if (task != null) task.cancel();
    }
}
