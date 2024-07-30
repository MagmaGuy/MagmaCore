package com.magmaguy.magmacore.util;

import com.magmaguy.magmacore.MagmaCore;
import net.md_5.bungee.api.ChatMessageType;
import net.md_5.bungee.api.chat.ClickEvent;
import net.md_5.bungee.api.chat.HoverEvent;
import net.md_5.bungee.api.chat.TextComponent;
import net.md_5.bungee.api.chat.hover.content.Text;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.Particle;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

public class Logger {
    private Logger() {
    }

    public static void info(String msg) {
        Bukkit.getLogger().info("[" + MagmaCore.getInstance().getRequestingPlugin().getName() + "] " + msg);
    }

    public static void warn(String msg) {
        Bukkit.getLogger().warning("[" + MagmaCore.getInstance().getRequestingPlugin().getName() + "] " + msg);
    }

    public static void warn(String msg, boolean printStackTrace) {
        Bukkit.getLogger().warning("[" + MagmaCore.getInstance().getRequestingPlugin().getName() + "] " + msg);
        if (printStackTrace) Thread.dumpStack();
    }

    public static void debug(String msg) {
        Bukkit.getLogger().warning("[" + MagmaCore.getInstance().getRequestingPlugin().getName() + "] Developer message:" + msg);
    }

    public static void sendMessage(CommandSender commandSender, String message) {
        commandSender.sendMessage(ChatColorConverter.convert("&8[" + MagmaCore.getInstance().getRequestingPlugin().getName() + "] &f" + message));
    }

    public static void sendSimpleMessage(CommandSender commandSender, String message) {
        commandSender.sendMessage(ChatColorConverter.convert( message));
    }

    public static TextComponent simpleMessage(String message) {
        return new TextComponent(ChatColorConverter.convert(message));
    }

    public static TextComponent hoverMessage(String message, String hoverMessage) {
        TextComponent textComponent = simpleMessage(message);
        textComponent.setHoverEvent(new HoverEvent(HoverEvent.Action.SHOW_TEXT, new Text(hoverMessage)));
        return textComponent;
    }

    public static TextComponent commandHoverMessage(String message, String hoverMessage, String commandString) {
        TextComponent textComponent = hoverMessage(message, ChatColorConverter.convert(hoverMessage));
        if (!commandString.isEmpty())
            textComponent.setClickEvent(new ClickEvent(ClickEvent.Action.RUN_COMMAND, commandString));
        return textComponent;
    }

    public static TextComponent hoverLinkMessage(String message, String hoverMessage, String link) {
        TextComponent textComponent = hoverMessage(message, hoverMessage);
        textComponent.setClickEvent(new ClickEvent(ClickEvent.Action.OPEN_URL, link));
        return textComponent;
    }

    public static void showLocation(Location location) {
        location.getWorld().spawnParticle(Particle.BLOCK_MARKER, location, 1, Material.BARRIER.createBlockData());
    }
}
