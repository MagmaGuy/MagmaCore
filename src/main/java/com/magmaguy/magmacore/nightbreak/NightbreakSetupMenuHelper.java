package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.menus.ContentPackage;
import com.magmaguy.magmacore.menus.SetupMenuIcons;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.ArrayList;
import java.util.List;

public final class NightbreakSetupMenuHelper {
    private static final String SEPARATOR = "<g:#8B0000:#CC4400:#DAA520>▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬</g>";

    private NightbreakSetupMenuHelper() {
    }

    public static ItemStack createItem(String displayName,
                                       List<String> description,
                                       Material material,
                                       String modelId,
                                       List<String> stateLore) {
        List<String> lore = new ArrayList<>(stateLore);
        lore.addAll(description);
        ItemStack itemStack = ItemStackGenerator.generateItemStack(material, displayName, lore);
        ItemMeta itemMeta = itemStack.getItemMeta();
        if (itemMeta != null) {
            itemMeta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
            itemMeta.addItemFlags(ItemFlag.HIDE_ENCHANTS);
            itemMeta.addItemFlags(ItemFlag.HIDE_ADDITIONAL_TOOLTIP);
            itemStack.setItemMeta(itemMeta);
        }
        SetupMenuIcons.applyItemModel(itemStack, modelId);
        return itemStack;
    }

    public static ItemStack createInstalledItem(String displayName, List<String> description) {
        return createItem(displayName, description, Material.GREEN_STAINED_GLASS_PANE,
                SetupMenuIcons.MODEL_CHECKMARK,
                List.of("&aContent is installed!", "&7Click to uninstall it."));
    }

    public static ItemStack createPartiallyInstalledItem(String displayName, List<String> description) {
        return createItem(displayName, description, Material.ORANGE_STAINED_GLASS_PANE,
                SetupMenuIcons.MODEL_GRAY_X,
                List.of("&6Content is only partially installed!",
                        "&7Some files appear to be missing or disabled.",
                        "&7Click to redownload and repair this package."));
    }

    public static ItemStack createNotInstalledItem(String displayName, List<String> description) {
        return createItem(displayName, description, Material.YELLOW_STAINED_GLASS_PANE,
                SetupMenuIcons.MODEL_GRAY_X,
                List.of("&eContent is downloaded but disabled.", "&7Click to install it."));
    }

    public static ItemStack createNotDownloadedItem(String displayName,
                                                    List<String> description,
                                                    String slug,
                                                    NightbreakAccount.AccessInfo accessInfo) {
        String modelId;
        if (slug == null || slug.isEmpty()) {
            modelId = SetupMenuIcons.MODEL_UNLOCKED;
        } else if (!NightbreakAccount.hasToken()) {
            modelId = SetupMenuIcons.MODEL_LOCKED_UNLINKED;
        } else if (accessInfo != null && accessInfo.hasAccess) {
            modelId = SetupMenuIcons.MODEL_UNLOCKED;
        } else {
            modelId = SetupMenuIcons.MODEL_LOCKED_UNPAID;
        }
        return createItem(displayName, description, Material.YELLOW_STAINED_GLASS_PANE,
                modelId,
                List.of("&eContent is not downloaded yet.", "&7Click to download it."));
    }

    public static ItemStack createNeedsAccessItem(String displayName,
                                                  List<String> description,
                                                  NightbreakAccount.AccessInfo accessInfo) {
        List<String> stateLore = new ArrayList<>();
        stateLore.add("&dNightbreak access is required.");
        stateLore.add("&7Click to view access links.");
        if (accessInfo != null) {
            if (accessInfo.patreonLink != null && !accessInfo.patreonLink.isEmpty()) {
                stateLore.add("&5Available on Patreon");
            }
            if (accessInfo.itchLink != null && !accessInfo.itchLink.isEmpty()) {
                stateLore.add("&5Available on itch.io");
            }
        }
        return createItem(displayName, description, Material.PURPLE_STAINED_GLASS_PANE,
                SetupMenuIcons.MODEL_LOCKED_UNPAID,
                stateLore);
    }

    public static ItemStack createOutOfDateUpdatableItem(String displayName,
                                                         List<String> description,
                                                         String slug) {
        String modelId;
        if (slug == null || slug.isEmpty()) {
            modelId = SetupMenuIcons.MODEL_UPDATE;
        } else if (!NightbreakAccount.hasToken()) {
            modelId = SetupMenuIcons.MODEL_UPDATE_UNLINKED;
        } else {
            modelId = SetupMenuIcons.MODEL_UPDATE;
        }
        return createItem(displayName, description, Material.YELLOW_STAINED_GLASS_PANE,
                modelId,
                List.of("&eAn update is available!", "&7Click to download the update."));
    }

    public static ItemStack createOutOfDateNoAccessItem(String displayName,
                                                        List<String> description) {
        return createItem(displayName, description, Material.ORANGE_STAINED_GLASS_PANE,
                SetupMenuIcons.MODEL_UPDATE_UNPAID,
                List.of("&6An update is available!",
                        "&7You need Nightbreak access before you can update it.",
                        "&7Click to view access links."));
    }

    public static void sendNoTokenPrompt(Player player, String pluginName, String contentUrl) {
        Logger.sendSimpleMessage(player, SEPARATOR);
        Logger.sendSimpleMessage(player, "&6Link your Nightbreak account to manage " + pluginName + " content in-game.");
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage("&2Account page: "),
                SpigotMessage.hoverLinkMessage("&ahttps://nightbreak.io/account/",
                        "&7Click to open your Nightbreak account page.",
                        "https://nightbreak.io/account/"));
        Logger.sendSimpleMessage(player, "&2Link command: &a/nightbreaklogin <token>");
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage("&2" + pluginName + " content: "),
                SpigotMessage.hoverLinkMessage("&a" + contentUrl,
                        "&7Click to browse Nightbreak content for " + pluginName + ".",
                        contentUrl));
        Logger.sendSimpleMessage(player, SEPARATOR);
    }

    public static void sendFirstTimeSetupResources(Player player, NightbreakFirstTimeSetupSpec spec) {
        Logger.sendSimpleMessage(player, "&8&m-----------------------------------------------------");
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage("&2Nightbreak account: "),
                SpigotMessage.hoverLinkMessage("&9&nhttps://nightbreak.io/account/",
                        "&7Click to open your Nightbreak account page.",
                        "https://nightbreak.io/account/"));
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage("&2Content page: "),
                SpigotMessage.hoverLinkMessage("&9&n" + spec.contentUrl(),
                        "&7Click to browse " + spec.pluginDisplayName() + " content.",
                        spec.contentUrl()));
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage("&2Content browser: "),
                SpigotMessage.commandHoverMessage("&a" + spec.setupCommand(),
                        "&7Click to open the " + spec.pluginDisplayName() + " setup menu.",
                        spec.setupCommand()));
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage("&2Bulk install: "),
                SpigotMessage.commandHoverMessage("&a" + spec.downloadAllCommand(),
                        "&7Click to install all available " + spec.pluginDisplayName() + " content.",
                        spec.downloadAllCommand()));
        if (spec.supportUrl() != null && !spec.supportUrl().isEmpty()) {
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage("&2Support: "),
                    SpigotMessage.hoverLinkMessage("&9&n" + spec.supportUrl(),
                            "&7Click to open the support link.",
                            spec.supportUrl()));
        }
        Logger.sendSimpleMessage(player, "&8&m-----------------------------------------------------");
    }

    public static void sendRecommendedSetupInstructions(Player player, NightbreakFirstTimeSetupSpec spec) {
        Logger.sendSimpleMessage(player, "&8&m-----------------------------------------------------");
        Logger.sendSimpleMessage(player, "&a" + spec.pluginDisplayName() + " setup is now marked as complete.");
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage("&7Step 1: get your Nightbreak token at "),
                SpigotMessage.hoverLinkMessage("&9&nhttps://nightbreak.io/account/",
                        "&7Click to open your Nightbreak account page.",
                        "https://nightbreak.io/account/"));
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage("&7Step 2: link it in-game with "),
                SpigotMessage.commandHoverMessage("&a/nightbreaklogin <token>",
                        "&7Click to run the Nightbreak login command.",
                        "/nightbreaklogin "));
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage("&7Step 3: install content with "),
                SpigotMessage.commandHoverMessage("&a" + spec.downloadAllCommand(),
                        "&7Click to download all available " + spec.pluginDisplayName() + " content.",
                        spec.downloadAllCommand()),
                SpigotMessage.simpleMessage(" &7or browse it with "),
                SpigotMessage.commandHoverMessage("&a" + spec.setupCommand(),
                        "&7Click to open the " + spec.pluginDisplayName() + " setup menu.",
                        spec.setupCommand()));
        for (String note : spec.recommendedNotes()) {
            Logger.sendSimpleMessage(player, note);
        }
        Logger.sendSimpleMessage(player, "&8&m-----------------------------------------------------");
    }

    public static void sendManualDownloadMessage(CommandSender sender,
                                                 String downloadLink,
                                                 String importsFolderName,
                                                 String reloadCommand) {
        Logger.sendSimpleMessage(sender, SEPARATOR);
        if (sender instanceof Player player) {
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage("&6Download this package here: "),
                    SpigotMessage.hoverLinkMessage("&9&n" + downloadLink,
                            "&7Click to open the download link.",
                            downloadLink));
        } else {
            Logger.sendSimpleMessage(sender, "&6Download this package here: &9&n" + downloadLink);
        }
        Logger.sendSimpleMessage(sender, "&7Then put the downloaded file in the &f" + importsFolderName + "&7 folder.");
        if (reloadCommand != null && !reloadCommand.isEmpty()) {
            Logger.sendSimpleMessage(sender, "&7Finish by running &a" + reloadCommand + "&7.");
        }
        Logger.sendSimpleMessage(sender, SEPARATOR);
    }

    public static void sendAccessInfo(Player player,
                                      String packageName,
                                      NightbreakAccount.AccessInfo accessInfo,
                                      String contentUrl) {
        Logger.sendSimpleMessage(player, SEPARATOR);
        Logger.sendSimpleMessage(player, "&6You do not currently have access to " + packageName + "&6.");
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage("&2Browse the content page: "),
                SpigotMessage.hoverLinkMessage("&a" + contentUrl,
                        "&7Click to open the Nightbreak content page.",
                        contentUrl));
        if (accessInfo != null) {
            if (accessInfo.patreonLink != null && !accessInfo.patreonLink.isEmpty()) {
                player.spigot().sendMessage(
                        SpigotMessage.simpleMessage("&2Patreon: "),
                        SpigotMessage.hoverLinkMessage("&a" + accessInfo.patreonLink,
                                "&7Click to open Patreon.",
                                accessInfo.patreonLink));
            }
            if (accessInfo.itchLink != null && !accessInfo.itchLink.isEmpty()) {
                player.spigot().sendMessage(
                        SpigotMessage.simpleMessage("&2itch.io: "),
                        SpigotMessage.hoverLinkMessage("&a" + accessInfo.itchLink,
                                "&7Click to open itch.io.",
                                accessInfo.itchLink));
            }
        }
        Logger.sendSimpleMessage(player, "&2Account link command: &a/nightbreaklogin <token>");
        Logger.sendSimpleMessage(player, SEPARATOR);
    }

    public static String getSeparator() {
        return SEPARATOR;
    }
}
