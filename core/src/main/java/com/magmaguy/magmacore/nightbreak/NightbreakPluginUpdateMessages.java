package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

final class NightbreakPluginUpdateMessages {
    private NightbreakPluginUpdateMessages() {
    }

    static void sendResult(CommandSender sender,
                           NightbreakPluginSpec spec,
                           NightbreakPluginUpdater.PluginUpdateDownload result) {
        if (sender == null) return;
        switch (result.status()) {
            case DOWNLOADED -> sendDownloaded(sender, spec, result);
            case UP_TO_DATE -> sendUpToDate(sender, spec, result);
            case NO_TOKEN -> sendNoToken(sender, spec, result);
            case NO_ACCESS -> sendNoAccess(sender, spec, result);
            case AUTH_FAILURE -> {
                NightbreakSetupMenuHelper.sendTokenUpdatePrompt(sender, spec.displayName());
                sendManualDownload(sender, result.downloadPageUrl());
            }
            case NIGHTBREAK_UNREACHABLE -> sendFailure(sender, spec, result,
                    "&cThe plugin update service could not be reached.",
                    "&7The public download page is still available if you want to update manually.");
            case CHECKSUM_FAILED -> sendFailure(sender, spec, result,
                    "&cThe downloaded plugin update failed checksum verification.",
                    "&7The file was not kept. Please use the manual page or try again later.");
            case DOWNLOAD_FAILED -> sendFailure(sender, spec, result,
                    "&cThe automatic plugin update could not be completed.",
                    "&7The public download page is still available if you want to update manually.");
        }
    }

    private static void sendDownloaded(CommandSender sender,
                                       NightbreakPluginSpec spec,
                                       NightbreakPluginUpdater.PluginUpdateDownload result) {
        sendSeparator(sender);
        Logger.sendSimpleMessage(sender, "<g:#228B22:#32CD32>Plugin update downloaded</g> &7for &f" + spec.displayName() + "&7.");
        Logger.sendSimpleMessage(sender, "&7Version: &f" + result.localVersion() + " &8-> &a" + result.remoteVersion());
        Logger.sendSimpleMessage(sender, "&eRestart the server to use the update.");
        Logger.sendSimpleMessage(sender, "&cDo not use plugin reloads for this. The running server is still using the old jar.");
        Logger.sendSimpleMessage(sender, "&8Downloaded file: &f" + result.downloadedFile().getPath());
        sendSeparator(sender);
    }

    static void broadcastRestartRequired(JavaPlugin plugin,
                                         NightbreakPluginSpec spec,
                                         NightbreakPluginUpdater.PluginUpdateDownload result,
                                         CommandSender initiator) {
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (!shouldReceiveRestartNotice(player, plugin, spec)) continue;
            Logger.sendTitle(player,
                    "<g:#228B22:#32CD32>" + spec.displayName() + " has updated</g>",
                    "&eServer restart required",
                    10,
                    100,
                    20);
            sendSeparator(player);
            Logger.sendSimpleMessage(player, "<g:#228B22:#32CD32>" + spec.displayName() + " has updated</g>&7.");
            Logger.sendSimpleMessage(player, "&7Downloaded version &a" + result.remoteVersion()
                    + " &7over the running version &f" + result.localVersion() + "&7.");
            Logger.sendSimpleMessage(player, "&eRestart the server now to use the updated plugin jar.");
            Logger.sendSimpleMessage(player, "&cUntil the restart, " + spec.displayName()
                    + " is still running the old jar and may become unstable if the server is not restarted soon.");
            if (initiator != null && initiator != player) {
                Logger.sendSimpleMessage(player, "&8Update command was run by: &7" + initiator.getName());
            }
            sendSeparator(player);
        }
    }

    private static boolean shouldReceiveRestartNotice(Player player, JavaPlugin plugin, NightbreakPluginSpec spec) {
        if (player == null || plugin == null || spec == null) return false;
        Set<String> permissions = new LinkedHashSet<>();
        permissions.add(spec.adminPermission());
        permissions.add(plugin.getName().toLowerCase(Locale.ROOT) + ".*");
        permissions.add(spec.pluginSlug().toLowerCase(Locale.ROOT) + ".*");
        permissions.add(spec.rootCommand().toLowerCase(Locale.ROOT) + ".*");
        for (String permission : permissions) {
            if (permission != null && !permission.isBlank() && player.hasPermission(permission)) {
                return true;
            }
        }
        return false;
    }

    private static void sendUpToDate(CommandSender sender,
                                     NightbreakPluginSpec spec,
                                     NightbreakPluginUpdater.PluginUpdateDownload result) {
        sendSeparator(sender);
        Logger.sendSimpleMessage(sender, "&a" + spec.displayName() + " is already up to date.");
        if (result.remoteVersion() != null) {
            Logger.sendSimpleMessage(sender, "&7Current release: &f" + result.remoteVersion());
        }
        sendSeparator(sender);
    }

    private static void sendNoToken(CommandSender sender,
                                    NightbreakPluginSpec spec,
                                    NightbreakPluginUpdater.PluginUpdateDownload result) {
        sendSeparator(sender);
        Logger.sendSimpleMessage(sender, "&eIn-game plugin updates are a supporter convenience.");
        sendCommand(sender,
                "&7Step 1: connect this server with ",
                "&a/nightbreaklogin <token>",
                "&7Click to prepare the token command.",
                "/nightbreaklogin ");
        sendLink(sender,
                "&7Step 2: get your account token from ",
                "&9&nhttps://nightbreak.io/account/",
                "&7Click to open the account token page.",
                "https://nightbreak.io/account/");
        sendManualDownload(sender, result.downloadPageUrl());
        sendSeparator(sender);
    }

    private static void sendNoAccess(CommandSender sender,
                                     NightbreakPluginSpec spec,
                                     NightbreakPluginUpdater.PluginUpdateDownload result) {
        sendSeparator(sender);
        Logger.sendSimpleMessage(sender, "&eThe server declined the automatic plugin update for &f" + spec.displayName() + "&e.");
        Logger.sendSimpleMessage(sender, "&7In-game plugin updates require an active supporter Patreon membership.");
        if (result.detail() != null && !result.detail().isBlank()) {
            Logger.sendSimpleMessage(sender, "&8Remote says: &7" + result.detail());
        }
        sendManualDownload(sender, result.downloadPageUrl());
        sendSeparator(sender);
    }

    private static void sendFailure(CommandSender sender,
                                    NightbreakPluginSpec spec,
                                    NightbreakPluginUpdater.PluginUpdateDownload result,
                                    String title,
                                    String guidance) {
        sendSeparator(sender);
        Logger.sendSimpleMessage(sender, title);
        Logger.sendSimpleMessage(sender, guidance);
        if (result.detail() != null && !result.detail().isBlank()) {
            Logger.sendSimpleMessage(sender, "&8Details: &7" + result.detail());
        }
        sendManualDownload(sender, result.downloadPageUrl());
        sendSeparator(sender);
    }

    private static void sendManualDownload(CommandSender sender, String downloadPageUrl) {
        sendLink(sender,
                "&7Manual download page: ",
                "&9&n" + downloadPageUrl,
                "&7Click to open the public download page.",
                downloadPageUrl);
    }

    private static void sendLink(CommandSender sender,
                                 String prefix,
                                 String label,
                                 String hover,
                                 String url) {
        if (sender instanceof Player player) {
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage(prefix),
                    SpigotMessage.hoverLinkMessage(label, hover, url));
        } else {
            Logger.sendSimpleMessage(sender, prefix + label);
        }
    }

    private static void sendCommand(CommandSender sender,
                                    String prefix,
                                    String label,
                                    String hover,
                                    String command) {
        if (sender instanceof Player player) {
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage(prefix),
                    SpigotMessage.commandHoverMessage(label, hover, command));
        } else {
            Logger.sendSimpleMessage(sender, prefix + label);
        }
    }

    private static void sendSeparator(CommandSender sender) {
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
    }
}
