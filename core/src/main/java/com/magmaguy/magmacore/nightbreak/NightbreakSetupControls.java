package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.menus.ContentPackage;
import com.magmaguy.magmacore.menus.MenuButton;
import com.magmaguy.magmacore.menus.NightbreakSetupIcons;
import com.magmaguy.magmacore.menus.SetupMenuBuilder;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.List;

public final class NightbreakSetupControls {
    private static final String DEFAULT_DISCORD_URL = "https://discord.gg/9f5QSka";

    private NightbreakSetupControls() {
    }

    public static SetupMenuBuilder prependStandardControls(SetupMenuBuilder builder,
                                                           JavaPlugin plugin,
                                                           NightbreakPluginSpec spec) {
        return builder
                .prependPackage(new PluginUpdatePackage(plugin, spec))
                .prependPackage(new RecommendedPluginsPackage(plugin, spec))
                .addToolbarButton(new AutoUpdateTogglePackage(plugin, spec));
    }

    public static SetupMenuBuilder prependRecommendationsOnly(SetupMenuBuilder builder,
                                                              JavaPlugin plugin,
                                                              NightbreakPluginSpec spec) {
        return builder.prependPackage(new RecommendedPluginsPackage(plugin, spec));
    }

    public static void openPluginSetupShell(JavaPlugin plugin, Player player, NightbreakPluginSpec spec) {
        SetupMenuBuilder builder = new SetupMenuBuilder(plugin, player)
                .title(spec.displayName() + " setup")
                .infoButton(setupInfoButton(spec))
                .packages(List.of());
        prependStandardControls(builder, plugin, spec).open();
    }

    public static MenuButton setupInfoButton(NightbreakPluginSpec spec) {
        return setupInfoButton(spec, spec.contentUrlWithTrailingSlash(), DEFAULT_DISCORD_URL);
    }

    public static MenuButton setupInfoButton(NightbreakPluginSpec spec, String wikiUrl) {
        return setupInfoButton(spec, wikiUrl, DEFAULT_DISCORD_URL);
    }

    public static MenuButton setupInfoButton(NightbreakPluginSpec spec, String wikiUrl, String discordUrl) {
        String recommendedCommand = "/" + spec.rootCommand() + " recommendedplugins";
        return new MenuButton(ItemStackGenerator.generateSkullItemStack("magmaguy",
                "&2Installation tips:",
                List.of("&2To setup the optional/recommended content for " + spec.displayName() + ":",
                        "&6- &fConnect your Nightbreak token from &anightbreak.io/account",
                        "&6- &fCheck recommended plugins with &a" + recommendedCommand,
                        "&6- &fCheck out MagmaGuy's other plugins",
                        "&f  with &a/nightbreak plugins",
                        "&2That's it!",
                        "&6Click for more info and links!"))) {
            @Override
            public void onClick(Player player) {
                player.closeInventory();
                Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
                Logger.sendSimpleMessage(player, "&6&l" + spec.displayName() + " setup resources:");
                player.spigot().sendMessage(
                        SpigotMessage.simpleMessage("&2&lDiscord support: "),
                        SpigotMessage.hoverLinkMessage("&a" + discordUrl,
                                "&7Click to open Discord support.",
                                discordUrl));
                player.spigot().sendMessage(
                        SpigotMessage.simpleMessage("&2&lAccount token: "),
                        SpigotMessage.hoverLinkMessage("&ahttps://nightbreak.io/account/",
                                "&7Click to open the account token page.",
                                "https://nightbreak.io/account/"));
                player.spigot().sendMessage(
                        SpigotMessage.simpleMessage("&2&lWiki / setup page: "),
                        SpigotMessage.hoverLinkMessage("&a" + wikiUrl,
                                "&7Click to open the setup documentation.",
                                wikiUrl));
                player.spigot().sendMessage(
                        SpigotMessage.commandHoverMessage("&2&lRecommended plugins: &a" + recommendedCommand,
                                "&7Click to see plugins that work well with " + spec.displayName() + ".",
                                recommendedCommand));
                player.spigot().sendMessage(
                        SpigotMessage.commandHoverMessage("&2&lPlugin catalog: &a/nightbreak plugins",
                                "&7Click to browse MagmaGuy's plugins.",
                                "/nightbreak plugins"));
                player.spigot().sendMessage(
                        SpigotMessage.commandHoverMessage((spec.hasContentPackages()
                                        ? "&2&lUpdate plugin & content: &a/"
                                        : "&2&lUpdate plugin: &a/") + spec.rootCommand() + " downloadall",
                                spec.hasContentPackages()
                                        ? "&7Click to download a plugin update if available and install content."
                                        : "&7Click to download a plugin update if available.",
                                "/" + spec.rootCommand() + " downloadall"));
                Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
            }
        };
    }

    private abstract static class StaticPackage extends ContentPackage {
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
        @Override protected ContentState getContentState() { return ContentState.INSTALLED; }
    }

    private static final class PluginUpdatePackage extends StaticPackage {
        private final JavaPlugin plugin;
        private final NightbreakPluginSpec spec;

        private PluginUpdatePackage(JavaPlugin plugin, NightbreakPluginSpec spec) {
            this.plugin = plugin;
            this.spec = spec;
        }

        @Override
        public ItemStack getItemstack() {
            NightbreakPluginUpdater.CachedPluginUpdateCheck snapshot = NightbreakPluginUpdater.getCachedUpdateCheck(plugin, spec);
            NightbreakPluginUpdater.PluginUpdateCheck check = snapshot.check();
            Material material;
            String name;
            String model;
            List<String> lore;
            if (check == null) {
                material = Material.YELLOW_STAINED_GLASS_PANE;
                name = snapshot.checking() ? "&eChecking Plugin Update" : "&ePlugin Update Pending";
                model = NightbreakSetupIcons.MODEL_UPDATE;
                lore = List.of("&7The server checks this automatically on boot,",
                        "&7then refreshes the result about once per hour.",
                        "&7Open this menu again shortly for the result.");
            } else if (check.updateAvailable()) {
                material = Material.YELLOW_STAINED_GLASS_PANE;
                name = "&ePlugin Update Available";
                model = NightbreakSetupIcons.MODEL_UPDATE;
                lore = List.of("&7Installed: &f" + check.localVersion(),
                        "&7Available: &a" + check.remoteVersion(),
                        "&7Click to download the plugin update.",
                        "&7Restart the server to use it.");
            } else if (check.remoteVersion() == null || check.remoteVersion().isBlank()) {
                material = Material.ORANGE_STAINED_GLASS_PANE;
                name = "&6Could Not Check Plugin Update";
                model = NightbreakSetupIcons.MODEL_UPDATE_UNLINKED;
                lore = List.of("&7The update service could not be reached.",
                        "&7The server will try again automatically.",
                        "&7Click for the public download page.");
            } else {
                material = Material.LIME_STAINED_GLASS_PANE;
                name = "&aPlugin Up To Date";
                model = NightbreakSetupIcons.MODEL_CHECKMARK;
                lore = List.of("&7Installed: &f" + check.localVersion(),
                        "&7Latest: &a" + check.remoteVersion(),
                        "&7The server refreshes this status about once per hour.");
            }
            ItemStack itemStack = ItemStackGenerator.generateItemStack(material, name, lore);
            NightbreakSetupIcons.applyItemModel(itemStack, model);
            return itemStack;
        }

        @Override
        public void onClick(Player player) {
            player.closeInventory();
            NightbreakPluginUpdater.CachedPluginUpdateCheck snapshot = NightbreakPluginUpdater.getCachedUpdateCheck(plugin, spec);
            NightbreakPluginUpdater.PluginUpdateCheck check = snapshot.check();
            if (check != null && check.updateAvailable()) {
                NightbreakPluginUpdater.downloadPluginUpdateAsync(plugin, spec, player, null);
                return;
            }
            Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
            if (check == null) {
                Logger.sendSimpleMessage(player, "&e" + spec.displayName() + " is checking for plugin updates.");
                Logger.sendSimpleMessage(player, "&7This happens automatically on boot and refreshes about once per hour.");
            } else if (check.remoteVersion() == null || check.remoteVersion().isBlank()) {
                Logger.sendSimpleMessage(player, "&e" + spec.displayName() + " could not check for plugin updates.");
                player.spigot().sendMessage(
                        SpigotMessage.simpleMessage("&7Manual download page: "),
                        SpigotMessage.hoverLinkMessage("&9&n" + spec.downloadPageUrl(),
                                "&7Click to open the public download page.",
                                spec.downloadPageUrl()));
            } else {
                Logger.sendSimpleMessage(player, "&a" + spec.displayName() + " is up to date.");
                Logger.sendSimpleMessage(player, "&7Installed: &f" + check.localVersion() + " &7Latest: &a" + check.remoteVersion());
            }
            Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
        }
    }

    private static final class AutoUpdateTogglePackage extends StaticPackage {
        private final JavaPlugin plugin;
        private final NightbreakPluginSpec spec;

        private AutoUpdateTogglePackage(JavaPlugin plugin, NightbreakPluginSpec spec) {
            this.plugin = plugin;
            this.spec = spec;
        }

        @Override
        public ItemStack getItemstack() {
            boolean enabled = NightbreakPluginUpdater.isAutoDownloadPluginUpdatesEnabled(plugin);
            boolean hasContent = spec.hasContentPackages();
            ItemStack itemStack = ItemStackGenerator.generateItemStack(
                    enabled ? Material.LIME_STAINED_GLASS_PANE : Material.RED_STAINED_GLASS_PANE,
                    enabled ? "&aAutomatic Updates: ON" : "&cAutomatic Updates: OFF",
                    enabled
                            ? List.of("&7" + spec.displayName() + " checks on startup.",
                            hasContent ? "&7Plugin and content updates download automatically." : "&7Plugin updates download automatically.",
                            "&7Restart the server to use updates.",
                            "&eClick to turn this off.")
                            : List.of("&7Default: off.",
                            "&7Click to let " + spec.displayName(),
                            hasContent ? "&7download plugin and content updates on startup." : "&7download plugin updates on startup.",
                            "&7Restart is still required."));
            NightbreakSetupIcons.applyItemModel(itemStack,
                    enabled ? NightbreakSetupIcons.MODEL_CHECKMARK : NightbreakSetupIcons.MODEL_RED_CROSS);
            return itemStack;
        }

        @Override
        public void onClick(Player player) {
            boolean newValue = !NightbreakPluginUpdater.isAutoDownloadPluginUpdatesEnabled(plugin);
            boolean saved = NightbreakPluginUpdater.setAutoDownloadPluginUpdatesEnabled(plugin, newValue);
            player.closeInventory();
            if (!saved) {
                Logger.sendSimpleMessage(player, "&cCould not save the automatic update setting. Check the console for details.");
                return;
            }
            Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
            Logger.sendSimpleMessage(player, newValue
                    ? "<g:#77dd77:#d4ff9d>Automatic updates enabled</g>&7 for &f" + spec.displayName() + "&7."
                    : "<g:#ff8a8a:#ffd1d1>Automatic updates disabled</g>&7 for &f" + spec.displayName() + "&7.");
            Logger.sendSimpleMessage(player, spec.hasContentPackages()
                    ? "&7This downloads plugin and content updates on startup. Restart the server to use a downloaded plugin update."
                    : "&7This downloads plugin updates on startup. Restart the server to use a downloaded plugin update.");
            Logger.sendSimpleMessage(player, NightbreakSetupMenuHelper.getSeparator());
        }
    }

    private static final class RecommendedPluginsPackage extends StaticPackage {
        private final JavaPlugin plugin;
        private final NightbreakPluginSpec spec;

        private RecommendedPluginsPackage(JavaPlugin plugin, NightbreakPluginSpec spec) {
            this.plugin = plugin;
            this.spec = spec;
        }

        @Override
        public ItemStack getItemstack() {
            int count = NightbreakPluginCatalog.forSpec(spec)
                    .map(entry -> NightbreakPluginCatalog.recommendationsFor(entry.slug()).size())
                    .orElse(0);
            boolean complete = allRecommendationsInstalled();
            int missing = missingRecommendationsCount();
            ItemStack itemStack = ItemStackGenerator.generateItemStack(complete ? Material.LIME_STAINED_GLASS_PANE : Material.BLUE_STAINED_GLASS_PANE,
                    "&bRecommended Plugins",
                    count == 0
                            ? List.of("&7No extra recommendations are currently listed.",
                            "&7Click to open the recommendation view.")
                            : complete
                            ? List.of("&aAll listed recommendations are installed.",
                            "&7Click to review the recommendation view.")
                            : List.of("&e" + missing + " recommendation" + (missing == 1 ? "" : "s") + " still missing.",
                            "&7Click to view " + count + " useful plugin" + (count == 1 ? "" : "s") + ".",
                            "&7Required and optional entries are labeled.",
                            "&7External plugins open official pages."));
            NightbreakSetupIcons.applyItemModel(itemStack, complete ? NightbreakSetupIcons.MODEL_CHECKMARK : NightbreakSetupIcons.MODEL_UNLOCKED);
            return itemStack;
        }

        @Override
        public void onClick(Player player) {
            NightbreakCatalogMenu.openRecommendations(plugin, player, spec);
        }

        private boolean allRecommendationsInstalled() {
            return NightbreakPluginCatalog.forSpec(spec)
                    .map(entry -> {
                        List<NightbreakPluginCatalog.Recommendation> recommendations = NightbreakPluginCatalog.recommendationsFor(entry.slug());
                        return !recommendations.isEmpty()
                                && recommendations.stream().allMatch(this::isRecommendationInstalled);
                    })
                    .orElse(false);
        }

        private boolean isRecommendationInstalled(NightbreakPluginCatalog.Recommendation recommendation) {
            return recommendation.entry()
                    .map(entry -> NightbreakPluginCatalog.isInstalled(entry)
                            && recommendation.dependencyNotes().stream().allMatch(this::isRecommendationInstalled))
                    .orElse(false);
        }

        private int missingRecommendationsCount() {
            return NightbreakPluginCatalog.forSpec(spec)
                    .map(entry -> NightbreakPluginCatalog.recommendationsFor(entry.slug()).stream()
                            .mapToInt(this::missingRecommendationCount)
                            .sum())
                    .orElse(0);
        }

        private int missingRecommendationCount(NightbreakPluginCatalog.Recommendation recommendation) {
            int missing = recommendation.entry()
                    .map(entry -> NightbreakPluginCatalog.isInstalled(entry) ? 0 : 1)
                    .orElse(1);
            missing += recommendation.dependencyNotes().stream()
                    .mapToInt(this::missingRecommendationCount)
                    .sum();
            return missing;
        }
    }
}
