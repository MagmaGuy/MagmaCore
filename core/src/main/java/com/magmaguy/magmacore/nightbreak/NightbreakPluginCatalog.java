package com.magmaguy.magmacore.nightbreak;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

public final class NightbreakPluginCatalog {
    private static final Map<String, Entry> ENTRIES = new LinkedHashMap<>();
    private static final Map<String, List<Recommendation>> RECOMMENDATIONS = new LinkedHashMap<>();

    static {
        add(new Entry("elitemobs", "EliteMobs", "EliteMobs", "em", "EliteMobs.jar",
                "https://nightbreak.io/plugin/elitemobs/",
                "https://nightbreak.io/plugin/elitemobs/download/",
                SourceType.NIGHTBREAK,
                List.of("&7Dynamic bosses, dungeons, quests, loot, arenas, and server RPG progression."),
                "40090"));
        add(new Entry("betterstructures", "BetterStructures", "BetterStructures", "bs", "BetterStructures.jar",
                "https://nightbreak.io/plugin/betterstructures/",
                "https://nightbreak.io/plugin/betterstructures/download/",
                SourceType.NIGHTBREAK,
                List.of("&7Random structures, schematics, modular generation, and world exploration content."),
                "103241"));
        add(new Entry("freeminecraftmodels", "FreeMinecraftModels", "FreeMinecraftModels", "fmm", "FreeMinecraftModels.jar",
                "https://nightbreak.io/plugin/freeminecraftmodels/",
                "https://nightbreak.io/plugin/freeminecraftmodels/download/",
                SourceType.NIGHTBREAK,
                List.of("&7Free custom model engine for mobs, props, furniture, and Bedrock-friendly assets."),
                "111660"));
        add(new Entry("resourcepackmanager", "ResourcePackManager", "ResourcePackManager", "rspm", "ResourcePackManager.jar",
                "https://nightbreak.io/plugin/resourcepackmanager/",
                "https://nightbreak.io/plugin/resourcepackmanager/download/",
                SourceType.NIGHTBREAK,
                List.of("&7Builds, hosts, merges, and delivers resource packs across supported plugins."),
                "118574"));
        add(new Entry("eternaltd", "EternalTD", "EternalTD", "etd", "EternalTD.jar",
                "https://nightbreak.io/plugin/eternaltd/",
                "https://nightbreak.io/plugin/eternaltd/download/",
                SourceType.NIGHTBREAK,
                List.of("&7Tower-defense minigame maps, waves, NPCs, towers, and installable content."),
                ""));
        add(new Entry("resurrectionchest", "ResurrectionChest", "ResurrectionChest", "resurrectionchest", "ResurrectionChest.jar",
                "https://nightbreak.io/plugin/resurrectionchest/",
                "https://nightbreak.io/plugin/resurrectionchest/download/",
                SourceType.NIGHTBREAK,
                List.of("&7Death chests that preserve items, with optional custom chest models."),
                "57541"));
        add(new Entry("cannonrtp", "CannonRTP", "CannonRTP", "wc", "CannonRTP.jar",
                "https://nightbreak.io/plugin/cannonrtp/",
                "https://nightbreak.io/plugin/cannonrtp/download/",
                SourceType.NIGHTBREAK,
                List.of("&7Cannon-based random teleporting with configurable landing and protection checks."),
                ""));
        add(new Entry("betterfood", "BetterFood", "BetterFood", "betterfood", "BetterFood.jar",
                "https://nightbreak.io/plugin/betterfood/",
                "https://nightbreak.io/plugin/betterfood/download/",
                SourceType.NIGHTBREAK,
                List.of("&7Quality-of-life eating controls for crafted and cooked food."),
                ""));
        add(new Entry("extractioncraft", "ExtractionCraft", "Extractioncraft", "extractioncraft", "Extractioncraft.jar",
                "https://nightbreak.io/plugin/extractioncraft/",
                "https://nightbreak.io/plugin/extractioncraft/download/",
                SourceType.NIGHTBREAK,
                List.of("&7Extraction match gameplay that pairs with structure and RPG content."),
                ""));

        add(new Entry("worldedit", "WorldEdit", "WorldEdit", "", "",
                "https://modrinth.com/plugin/worldedit",
                "https://modrinth.com/plugin/worldedit",
                SourceType.EXTERNAL,
                List.of("&7Required structure editing library used to load and paste schematics."),
                "",
                List.of("FastAsyncWorldEdit")));
        add(new Entry("worldguard", "WorldGuard", "WorldGuard", "", "",
                "https://modrinth.com/plugin/worldguard",
                "https://modrinth.com/plugin/worldguard",
                SourceType.EXTERNAL,
                List.of("&7Region protection layer used by BetterStructures guided setup."),
                ""));
        add(new Entry("libsdisguises", "LibsDisguises Free", "LibsDisguises", "", "",
                "https://www.spigotmc.org/resources/libs-disguises-free.81/",
                "https://www.spigotmc.org/resources/libs-disguises-free.81/",
                SourceType.EXTERNAL,
                List.of("&7Adds disguise support for custom NPC and player appearances."),
                ""));
        add(new Entry("packetevents", "PacketEvents", "packetevents", "", "",
                "https://modrinth.com/plugin/packetevents",
                "https://modrinth.com/plugin/packetevents",
                SourceType.EXTERNAL,
                List.of("&7Required packet library used by current LibsDisguises builds."),
                ""));

        recommend("elitemobs",
                rec("betterstructures", RelationType.RECOMMENDED),
                rec("freeminecraftmodels", RelationType.RECOMMENDED),
                rec("resourcepackmanager", RelationType.RECOMMENDED),
                rec("libsdisguises", RelationType.RECOMMENDED, rec("packetevents", RelationType.REQUIRED)));
        recommend("betterstructures",
                rec("worldedit", RelationType.REQUIRED),
                rec("worldguard", RelationType.REQUIRED),
                rec("freeminecraftmodels", RelationType.RECOMMENDED),
                rec("elitemobs", RelationType.RECOMMENDED),
                rec("resourcepackmanager", RelationType.RECOMMENDED));
        recommend("resourcepackmanager",
                rec("freeminecraftmodels", RelationType.ENCOURAGED));
        recommend("freeminecraftmodels",
                rec("resourcepackmanager", RelationType.RECOMMENDED),
                rec("elitemobs", RelationType.ENCOURAGED),
                rec("betterstructures", RelationType.ENCOURAGED));
        recommend("eternaltd",
                rec("freeminecraftmodels", RelationType.RECOMMENDED),
                rec("resourcepackmanager", RelationType.RECOMMENDED),
                rec("libsdisguises", RelationType.ENCOURAGED, rec("packetevents", RelationType.REQUIRED)));
        recommend("extractioncraft",
                rec("elitemobs", RelationType.RECOMMENDED),
                rec("betterstructures", RelationType.RECOMMENDED),
                rec("resourcepackmanager", RelationType.RECOMMENDED),
                rec("freeminecraftmodels", RelationType.RECOMMENDED));
        recommend("resurrectionchest",
                rec("freeminecraftmodels", RelationType.RECOMMENDED),
                rec("resourcepackmanager", RelationType.RECOMMENDED));
        recommend("cannonrtp",
                rec("freeminecraftmodels", RelationType.RECOMMENDED),
                rec("resourcepackmanager", RelationType.RECOMMENDED),
                rec("worldguard", RelationType.ENCOURAGED));
        recommend("betterfood");
    }

    private NightbreakPluginCatalog() {
    }

    public static List<Entry> entries() {
        return List.copyOf(ENTRIES.values());
    }

    public static Optional<Entry> find(String slug) {
        if (slug == null) return Optional.empty();
        return Optional.ofNullable(ENTRIES.get(normalize(slug)));
    }

    public static Optional<Entry> forSpec(NightbreakPluginSpec spec) {
        if (spec == null) return Optional.empty();
        Optional<Entry> bySlug = find(spec.pluginSlug());
        if (bySlug.isPresent()) return bySlug;
        return ENTRIES.values().stream()
                .filter(entry -> entry.rootCommand().equalsIgnoreCase(spec.rootCommand()))
                .findFirst();
    }

    public static List<Recommendation> recommendationsFor(String slug) {
        if (slug == null) return List.of();
        return RECOMMENDATIONS.getOrDefault(normalize(slug), List.of());
    }

    public static boolean isInstalled(Entry entry) {
        return installedPlugin(entry).isPresent();
    }

    public static Optional<Plugin> installedPlugin(Entry entry) {
        if (entry == null || entry.pluginName() == null || entry.pluginName().isBlank()) return Optional.empty();

        for (String pluginName : entry.allPluginNames()) {
            Plugin exactMatch = Bukkit.getPluginManager().getPlugin(pluginName);
            if (exactMatch != null) return Optional.of(exactMatch);
        }

        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            for (String pluginName : entry.allPluginNames()) {
                if (plugin.getName().equalsIgnoreCase(pluginName)) return Optional.of(plugin);
            }
        }
        return Optional.empty();
    }

    public static String setupCommand(Entry entry) {
        if (entry == null || entry.rootCommand().isBlank()) return "";
        return "/" + entry.rootCommand() + " setup";
    }

    public static String recommendedPluginsCommand(Entry entry) {
        if (entry == null || entry.rootCommand().isBlank()) return "";
        return "/" + entry.rootCommand() + " recommendedplugins";
    }

    private static void add(Entry entry) {
        ENTRIES.put(normalize(entry.slug()), entry);
    }

    private static Recommendation rec(String slug, RelationType relationType, Recommendation... dependencyNotes) {
        return new Recommendation(slug, relationType, List.of(dependencyNotes));
    }

    private static void recommend(String ownerSlug, Recommendation... recommendations) {
        RECOMMENDATIONS.put(normalize(ownerSlug), List.of(recommendations));
    }

    private static String normalize(String slug) {
        return slug.toLowerCase(Locale.ROOT).replace("_", "-").trim();
    }

    public enum SourceType {
        NIGHTBREAK,
        EXTERNAL
    }

    public enum RelationType {
        REQUIRED("&cRequired", "&7This plugin is needed for the guided setup."),
        RECOMMENDED("&aRecommended", "&7This plugin adds useful functionality here."),
        ENCOURAGED("&eEncouraged", "&7This plugin is useful when you want this extra feature.");

        private final String displayName;
        private final String description;

        RelationType(String displayName, String description) {
            this.displayName = displayName;
            this.description = description;
        }

        public String displayName() {
            return displayName;
        }

        public String description() {
            return description;
        }
    }

    public record Entry(String slug,
                        String displayName,
                        String pluginName,
                        String rootCommand,
                        String jarFileName,
                        String publicPageUrl,
                        String downloadPageUrl,
                        SourceType sourceType,
                        List<String> summaryLore,
                        String spigotResourceId,
                        List<String> pluginAliases) {
        public Entry(String slug,
                     String displayName,
                     String pluginName,
                     String rootCommand,
                     String jarFileName,
                     String publicPageUrl,
                     String downloadPageUrl,
                     SourceType sourceType,
                     List<String> summaryLore,
                     String spigotResourceId) {
            this(slug, displayName, pluginName, rootCommand, jarFileName, publicPageUrl, downloadPageUrl, sourceType, summaryLore, spigotResourceId, List.of());
        }

        public Entry {
            summaryLore = summaryLore == null ? List.of() : List.copyOf(summaryLore);
            rootCommand = rootCommand == null ? "" : rootCommand;
            jarFileName = jarFileName == null ? "" : jarFileName;
            spigotResourceId = spigotResourceId == null ? "" : spigotResourceId;
            pluginAliases = pluginAliases == null ? List.of() : List.copyOf(pluginAliases);
        }

        public List<String> allPluginNames() {
            List<String> pluginNames = new ArrayList<>();
            if (pluginName != null && !pluginName.isBlank()) pluginNames.add(pluginName);
            pluginNames.addAll(pluginAliases);
            return pluginNames.stream()
                    .filter(name -> name != null && !name.isBlank())
                    .distinct()
                    .toList();
        }
    }

    public record Recommendation(String slug,
                                 RelationType relationType,
                                 List<Recommendation> dependencyNotes) {
        public Recommendation {
            dependencyNotes = dependencyNotes == null ? List.of() : List.copyOf(dependencyNotes);
        }

        public Optional<Entry> entry() {
            return NightbreakPluginCatalog.find(slug);
        }
    }
}
