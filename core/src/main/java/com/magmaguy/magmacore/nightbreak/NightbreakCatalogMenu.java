package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.menus.ContentPackage;
import com.magmaguy.magmacore.menus.MenuButton;
import com.magmaguy.magmacore.menus.NightbreakSetupIcons;
import com.magmaguy.magmacore.menus.SetupMenuBuilder;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.List;

public final class NightbreakCatalogMenu {
    private NightbreakCatalogMenu() {
    }

    public static void openGlobalCatalog(JavaPlugin ownerPlugin, Player player) {
        List<ContentPackage> packages = NightbreakPluginCatalog.entries().stream()
                .filter(entry -> entry.sourceType() == NightbreakPluginCatalog.SourceType.NIGHTBREAK)
                .map(entry -> (ContentPackage) new CatalogEntryPackage(ownerPlugin, entry, null, List.of()))
                .toList();

        new SetupMenuBuilder(ownerPlugin, player)
                .title("Plugin catalog")
                .infoButton(infoButton("&6&lPlugin catalog",
                        List.of("&7Browse the available plugins.",
                                "&7Installed plugins can open their setup menu.",
                                "&7Missing plugins can be downloaded in-game by supporters.")))
                .packages(packages)
                .open();
    }

    public static void openRecommendations(JavaPlugin ownerPlugin, Player player, NightbreakPluginSpec spec) {
        NightbreakPluginCatalog.Entry ownerEntry = NightbreakPluginCatalog.forSpec(spec).orElse(null);
        if (ownerEntry == null) {
            Logger.sendSimpleMessage(player, "&eNo recommendation catalog entry is registered for " + spec.displayName() + ".");
            return;
        }

        List<ContentPackage> packages = new ArrayList<>();
        for (NightbreakPluginCatalog.Recommendation recommendation : NightbreakPluginCatalog.recommendationsFor(ownerEntry.slug())) {
            recommendation.entry().ifPresent(entry ->
                    packages.add(new CatalogEntryPackage(ownerPlugin, entry, recommendation.relationType(), recommendation.dependencyNotes())));
        }

        new SetupMenuBuilder(ownerPlugin, player)
                .title(ownerEntry.displayName() + " recommendations")
                .infoButton(infoButton("&6&lRecommended plugins for " + ownerEntry.displayName(),
                        List.of("&7These entries are useful with " + ownerEntry.displayName() + ".",
                                "&7Required entries are marked separately.",
                                "&7External plugins open their public download page.")))
                .packages(packages)
                .open();

        if (packages.isEmpty()) {
            Logger.sendSimpleMessage(player, "&a" + ownerEntry.displayName() + " does not currently have extra plugin recommendations.");
        }
    }

    public static void sendGlobalCatalog(CommandSender sender) {
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
        Logger.sendSimpleMessage(sender, "<g:#8B0000:#CC4400:#DAA520>Available plugins</g>");
        for (NightbreakPluginCatalog.Entry entry : NightbreakPluginCatalog.entries()) {
            if (entry.sourceType() != NightbreakPluginCatalog.SourceType.NIGHTBREAK) continue;
            sendCatalogLine(sender, entry, null);
        }
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
    }

    public static void sendRecommendations(CommandSender sender, NightbreakPluginSpec spec) {
        NightbreakPluginCatalog.Entry ownerEntry = NightbreakPluginCatalog.forSpec(spec).orElse(null);
        if (ownerEntry == null) {
            Logger.sendSimpleMessage(sender, "&eNo recommendation catalog entry is registered for " + spec.displayName() + ".");
            return;
        }

        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
        Logger.sendSimpleMessage(sender, "<g:#8B0000:#CC4400:#DAA520>" + ownerEntry.displayName() + " recommendations</g>");
        List<NightbreakPluginCatalog.Recommendation> recommendations = NightbreakPluginCatalog.recommendationsFor(ownerEntry.slug());
        if (recommendations.isEmpty()) {
            Logger.sendSimpleMessage(sender, "&aNo extra plugin recommendations are currently listed.");
        }
        for (NightbreakPluginCatalog.Recommendation recommendation : recommendations) {
            recommendation.entry().ifPresent(entry -> sendCatalogLine(sender, entry, recommendation.relationType()));
        }
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
    }

    public static void sendLoginSuccess(CommandSender sender, JavaPlugin plugin) {
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
        Logger.sendSimpleMessage(sender, "<g:#77dd77:#d4ff9d>This server is connected.</g>");
        Logger.sendSimpleMessage(sender, "&7Plugins can now use this token for in-game content installs and update downloads.");
        sendCommand(sender, "&7Browse plugins: ", "&a/nightbreak plugins",
                "&7Click to browse available plugins.", "/nightbreak plugins");
        NightbreakPluginCatalog.entries().stream()
                .filter(entry -> plugin != null && entry.pluginName().equalsIgnoreCase(plugin.getName()))
                .findFirst()
                .ifPresent(entry -> sendCommand(sender, "&7Open setup: ", "&a" + NightbreakPluginCatalog.setupCommand(entry),
                        "&7Click to open the " + entry.displayName() + " setup menu.",
                        NightbreakPluginCatalog.setupCommand(entry)));
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
    }

    public static void sendInitializeFollowup(Player player, NightbreakFirstTimeSetupSpec spec) {
        if (player == null || spec == null) return;
        Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
        Logger.sendSimpleMessage(player, "<g:#8B0000:#CC4400:#DAA520>" + spec.pluginDisplayName() + " setup</g>");
        Logger.sendSimpleMessage(player, "&7Use the setup menu for content, updates, and update settings.");
        sendCommand(player, "&7Setup menu: ", "&a" + spec.setupCommand(),
                "&7Click to open the " + spec.pluginDisplayName() + " setup menu.", spec.setupCommand());
        String recommendedCommand = recommendedCommandFromSetup(spec.setupCommand());
        if (!recommendedCommand.isBlank()) {
            sendCommand(player, "&7Recommended plugins: ", "&a" + recommendedCommand,
                    "&7Click to see plugins that work well with " + spec.pluginDisplayName() + ".",
                    recommendedCommand);
        }
        if (!NightbreakAccount.hasToken()) {
            Logger.sendSimpleMessage(player, "&7Connect this server so " + spec.pluginDisplayName() + " can install content and download plugin updates from in-game.");
            sendLink(player, "&7Account token: ", "&9&nhttps://nightbreak.io/account/",
                    "&7Click to open the account token page.", "https://nightbreak.io/account/");
            sendCommand(player, "&7Link command: ", "&a/nightbreaklogin <token>",
                    "&7Click to prepare the token command.", "/nightbreaklogin ");
        }
        Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
    }

    private static void sendCatalogLine(CommandSender sender,
                                        NightbreakPluginCatalog.Entry entry,
                                        NightbreakPluginCatalog.RelationType relationType) {
        String prefix = relationType == null ? "&7- " : "&7- " + relationType.displayName() + "&7: ";
        boolean installed = NightbreakPluginCatalog.isInstalled(entry);
        String status = installed ? " &a(installed)" : " &e(not installed)";
        if (entry.sourceType() == NightbreakPluginCatalog.SourceType.EXTERNAL) {
            status = installed ? " &a(installed external)" : " &e(not installed external)";
        }
        Logger.sendSimpleMessage(sender, prefix + "&f" + entry.displayName() + status);
        if (!entry.summaryLore().isEmpty()) {
            Logger.sendSimpleMessage(sender, "  " + entry.summaryLore().get(0));
        }
        if (entry.sourceType() == NightbreakPluginCatalog.SourceType.NIGHTBREAK && NightbreakPluginCatalog.isInstalled(entry)) {
            sendCommand(sender, "  &7Setup: ", "&a" + NightbreakPluginCatalog.setupCommand(entry),
                    "&7Click to open the setup menu.", NightbreakPluginCatalog.setupCommand(entry));
        } else {
            sendLink(sender, "  &7Download: ", "&9&n" + entry.downloadPageUrl(),
                    "&7Click to open the public download page.", entry.downloadPageUrl());
        }
    }

    private static MenuButton infoButton(String name, List<String> lore) {
        return new MenuButton(ItemStackGenerator.generateSkullItemStack("magmaguy", name, lore)) {
            @Override
            public void onClick(Player player) {
                player.closeInventory();
                Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
                Logger.sendSimpleMessage(player, name);
                for (String line : lore) {
                    Logger.sendSimpleMessage(player, line);
                }
                Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
            }
        };
    }

    private static String recommendedCommandFromSetup(String setupCommand) {
        if (setupCommand == null || setupCommand.isBlank()) return "";
        String trimmed = setupCommand.trim();
        if (trimmed.endsWith(" setup")) {
            return trimmed.substring(0, trimmed.length() - " setup".length()) + " recommendedplugins";
        }
        return "";
    }

    private static void sendCommand(CommandSender sender, String prefix, String label, String hover, String command) {
        if (sender instanceof Player player) {
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage(prefix),
                    SpigotMessage.commandHoverMessage(label, hover, command));
        } else {
            Logger.sendSimpleMessage(sender, prefix + label);
        }
    }

    private static void sendLink(CommandSender sender, String prefix, String label, String hover, String url) {
        if (sender instanceof Player player) {
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage(prefix),
                    SpigotMessage.hoverLinkMessage(label, hover, url));
        } else {
            Logger.sendSimpleMessage(sender, prefix + label);
        }
    }

    private static final class CatalogEntryPackage extends ContentPackage {
        private final JavaPlugin ownerPlugin;
        private final NightbreakPluginCatalog.Entry entry;
        private final NightbreakPluginCatalog.RelationType relationType;
        private final List<NightbreakPluginCatalog.Recommendation> dependencyNotes;

        private CatalogEntryPackage(JavaPlugin ownerPlugin,
                                    NightbreakPluginCatalog.Entry entry,
                                    NightbreakPluginCatalog.RelationType relationType,
                                    List<NightbreakPluginCatalog.Recommendation> dependencyNotes) {
            this.ownerPlugin = ownerPlugin;
            this.entry = entry;
            this.relationType = relationType;
            this.dependencyNotes = dependencyNotes == null ? List.of() : List.copyOf(dependencyNotes);
        }

        @Override
        public ItemStack getItemstack() {
            boolean installed = NightbreakPluginCatalog.isInstalled(entry);
            Material material = installed ? Material.LIME_STAINED_GLASS_PANE : Material.YELLOW_STAINED_GLASS_PANE;
            String model = installed ? NightbreakSetupIcons.MODEL_CHECKMARK : NightbreakSetupIcons.MODEL_CROWN_YELLOW;

            List<String> lore = new ArrayList<>();
            if (relationType != null) {
                lore.add(relationType.displayName());
                lore.add(relationType.description());
                lore.add("");
            }
            lore.addAll(entry.summaryLore());
            if (!dependencyNotes.isEmpty()) {
                lore.add("");
                for (NightbreakPluginCatalog.Recommendation dependencyNote : dependencyNotes) {
                    dependencyNote.entry().ifPresent(dependencyEntry -> {
                        boolean dependencyInstalled = NightbreakPluginCatalog.isInstalled(dependencyEntry);
                        lore.add((dependencyInstalled ? "&aInstalled required plugin: &f" : "&eRequired plugin not installed: &f")
                                + dependencyEntry.displayName());
                    });
                }
            }
            lore.add("");
            if (entry.sourceType() == NightbreakPluginCatalog.SourceType.EXTERNAL) {
                if (installed) {
                    lore.add("&aInstalled external plugin.");
                    lore.add("&7Click for the official page.");
                } else {
                    lore.add("&eNot installed on this server.");
                    lore.add("&7Click for the official download page.");
                }
            } else if (installed) {
                lore.add("&aInstalled. Click to open setup.");
            } else {
                lore.add("&eNot installed. Click to download it.");
                lore.add("&7Restart the server after download.");
            }

            ItemStack itemStack = ItemStackGenerator.generateItemStack(material, "&f" + entry.displayName(), lore);
            NightbreakSetupIcons.applyItemModel(itemStack, model);
            return itemStack;
        }

        @Override
        public void onClick(Player player) {
            player.closeInventory();
            if (entry.sourceType() == NightbreakPluginCatalog.SourceType.EXTERNAL) {
                Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
                Logger.sendSimpleMessage(player, "&f" + entry.displayName());
                Logger.sendSimpleMessage(player, NightbreakPluginCatalog.isInstalled(entry)
                        ? "&aInstalled on this server."
                        : "&eNot installed on this server.");
                for (String line : entry.summaryLore()) {
                    Logger.sendSimpleMessage(player, line);
                }
                sendLink(player, "&7Official page: ", "&9&n" + entry.downloadPageUrl(),
                        "&7Click to open the official download page.", entry.downloadPageUrl());
                Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
                return;
            }

            if (NightbreakPluginCatalog.isInstalled(entry)) {
                String setupCommand = NightbreakPluginCatalog.setupCommand(entry);
                if (!setupCommand.isBlank()) {
                    Bukkit.dispatchCommand(player, setupCommand.substring(1));
                    return;
                }
            }

            NightbreakPluginInstaller.downloadPluginAsync(ownerPlugin, entry, player, null);
        }

        @Override protected ItemStack getInstalledItemStack() { return getItemstack(); }
        @Override protected ItemStack getPartiallyInstalledItemStack() { return getItemstack(); }
        @Override protected ItemStack getNotInstalledItemStack() { return getItemstack(); }
        @Override protected ItemStack getNotDownloadedItemStack() { return getItemstack(); }
        @Override protected ItemStack getNeedsAccessItemStack() { return getItemstack(); }
        @Override protected ItemStack getOutOfDateUpdatableItemStack() { return getItemstack(); }
        @Override protected ItemStack getOutOfDateNoAccessItemStack() { return getItemstack(); }
        @Override protected void doInstall(Player player) { }
        @Override protected void doUninstall(Player player) { }
        @Override protected void doDownload(Player player) { }
        @Override protected void doShowAccessInfo(Player player) { }
        @Override protected ContentState getContentState() {
            return NightbreakPluginCatalog.isInstalled(entry) ? ContentState.INSTALLED : ContentState.NOT_DOWNLOADED;
        }
    }
}
