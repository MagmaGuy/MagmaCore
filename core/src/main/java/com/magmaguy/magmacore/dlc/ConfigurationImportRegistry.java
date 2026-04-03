package com.magmaguy.magmacore.dlc;

import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.nio.file.Path;
import java.util.EnumMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

final class ConfigurationImportRegistry {

    /**
     * Resolves the FMM models folder, preferring the legacy "Models" name if it
     * already exists on disk, otherwise using lowercase "models".
     */
    static Path resolveFmmModelsFolder(ConfigurationImporter importer) {
        Path fmmPath = importer.getFreeMinecraftModelsPath();
        File legacy = fmmPath.resolve("Models").toFile();
        if (legacy.exists()) return legacy.toPath();
        return fmmPath.resolve("models");
    }
    private static final Map<ConfigurationImporter.PluginPlatform, Map<String, Function<ConfigurationImporter, Path>>> platformResolvers =
            new EnumMap<>(ConfigurationImporter.PluginPlatform.class);
    private static final Map<String, Function<ConfigurationImporter, Path>> globalResolvers = new HashMap<>();
    private static final Map<ConfigurationImporter.PluginPlatform, Function<ConfigurationImporter, Path>> platformFallbackResolvers =
            new EnumMap<>(ConfigurationImporter.PluginPlatform.class);
    private static final Set<String> skippedFolders = new HashSet<>();

    static {
        registerDefaults();
    }

    private ConfigurationImportRegistry() {
    }

    static Path resolve(ConfigurationImporter importer,
                        String folder,
                        ConfigurationImporter.PluginPlatform platform) {
        String normalizedFolder = normalize(folder);
        Function<ConfigurationImporter, Path> globalResolver = globalResolvers.get(normalizedFolder);
        if (globalResolver != null) {
            return globalResolver.apply(importer);
        }

        Function<ConfigurationImporter, Path> platformResolver = platformResolvers
                .getOrDefault(platform, Map.of())
                .get(normalizedFolder);
        if (platformResolver != null) {
            return platformResolver.apply(importer);
        }

        Function<ConfigurationImporter, Path> fallbackResolver = platformFallbackResolvers.get(platform);
        if (fallbackResolver != null) {
            return fallbackResolver.apply(importer);
        }

        return null;
    }

    static boolean isSkippedFolder(String folder) {
        if (folder == null) return false;
        return skippedFolders.contains(normalize(folder));
    }

    private static void registerDefaults() {
        registerSkipped("pack.meta");
        registerGlobal("worldcontainer", importer -> {
            try {
                File worldContainer = Bukkit.getWorldContainer().getCanonicalFile();
                return worldContainer.toPath().normalize().toAbsolutePath();
            } catch (IOException e) {
                throw new RuntimeException("Failed to resolve world container path canonically!", e);
            }
        });
        registerGlobal("modelengine", importer -> {
            importer.markModelsInstalled();
            if (Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels")) {
                return resolveFmmModelsFolder(importer);
            }
            if (Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) {
                return importer.getModelEnginePath().resolve("blueprints");
            }
            return resolveFmmModelsFolder(importer);
        });
        registerGlobal("models", importer -> {
            importer.markModelsInstalled();
            if (Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels")) {
                return resolveFmmModelsFolder(importer);
            }
            if (Bukkit.getPluginManager().isPluginEnabled("ModelEngine")) {
                return importer.getModelEnginePath().resolve("blueprints");
            }
            return resolveFmmModelsFolder(importer);
        });

        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "custombosses", importer -> importer.getEliteMobsPath().resolve("custombosses"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "customitems", importer -> importer.getEliteMobsPath().resolve("customitems"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "customtreasurechests", importer -> importer.getEliteMobsPath().resolve("customtreasurechests"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "dungeonpackages", importer -> importer.getEliteMobsPath().resolve("content_packages"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "content_packages", importer -> importer.getEliteMobsPath().resolve("content_packages"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "customevents", importer -> importer.getEliteMobsPath().resolve("customevents"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "customspawns", importer -> importer.getEliteMobsPath().resolve("customspawns"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "customquests", importer -> importer.getEliteMobsPath().resolve("customquests"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "customarenas", importer -> importer.getEliteMobsPath().resolve("customarenas"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "npcs", importer -> importer.getEliteMobsPath().resolve("npcs"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "wormholes", importer -> importer.getEliteMobsPath().resolve("wormholes"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "powers", importer -> importer.getEliteMobsPath().resolve("powers"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "world_blueprints", importer -> importer.getEliteMobsPath().resolve("world_blueprints"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ELITEMOBS, "resource_pack", importer -> importer.getEliteMobsPath().resolve("resource_pack"));

        registerPlatformFolder(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES, "content_packages", importer -> importer.getBetterStructuresPath().resolve("content_packages"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES, "schematics", importer -> importer.getBetterStructuresPath().resolve("schematics"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES, "spawn_pools", importer -> importer.getBetterStructuresPath().resolve("spawn_pools"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES, "components", importer -> importer.getBetterStructuresPath().resolve("components"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES, "modules", importer -> importer.getBetterStructuresPath().resolve("modules"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES, "module_generators", importer -> importer.getBetterStructuresPath().resolve("module_generators"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES, "elitemobs", ConfigurationImporter::getEliteMobsPath);
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES, "custombosses", importer -> importer.getEliteMobsPath().resolve("custombosses"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES, "customitems", importer -> importer.getEliteMobsPath().resolve("customitems"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES, "customtreasurechests", importer -> importer.getEliteMobsPath().resolve("customtreasurechests"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.BETTERSTRUCTURES, "powers", importer -> importer.getEliteMobsPath().resolve("powers"));

        registerPlatformFolder(ConfigurationImporter.PluginPlatform.RESURRECTIONCHEST, "content_packages", importer -> importer.getResurrectionChestPath().resolve("content_packages"));

        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "content_packages", importer -> importer.getExtractioncraftPath().resolve("content_packages"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "customquests", importer -> importer.getEliteMobsPath().resolve("customquests"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "npcs", importer -> importer.getEliteMobsPath().resolve("npcs"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "loot_pools", importer -> importer.getExtractioncraftPath().resolve("loot_pools"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "loot_tables", importer -> importer.getExtractioncraftPath().resolve("loot_tables"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "custombosses", importer -> importer.getEliteMobsPath().resolve("custombosses"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "customitems", importer -> importer.getEliteMobsPath().resolve("customitems"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "customtreasurechests", importer -> importer.getEliteMobsPath().resolve("customtreasurechests"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "powers", importer -> importer.getEliteMobsPath().resolve("powers"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "spawn_pools", importer -> importer.getBetterStructuresPath().resolve("spawn_pools"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "components", importer -> importer.getBetterStructuresPath().resolve("components"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "modules", importer -> importer.getBetterStructuresPath().resolve("modules"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.EXTRACTIONCRAFT, "module_generators", importer -> importer.getBetterStructuresPath().resolve("module_generators"));

        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ETERNALTD, "npcs", importer -> importer.getEternalTDPath().resolve("npcs"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ETERNALTD, "levels", importer -> importer.getEternalTDPath().resolve("levels"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ETERNALTD, "waves", importer -> importer.getEternalTDPath().resolve("waves"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.ETERNALTD, "worlds", importer -> importer.getEternalTDPath().resolve("worlds"));

        registerPlatformFolder(ConfigurationImporter.PluginPlatform.MEGABLOCKSURVIVORS, "content_packages", importer -> importer.getMegaBlockSurvivorsPath().resolve("content_packages"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.MEGABLOCKSURVIVORS, "schematics", importer -> importer.getMegaBlockSurvivorsPath().resolve("schematics"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.MEGABLOCKSURVIVORS, "worlds", importer -> importer.getMegaBlockSurvivorsPath().resolve("worlds"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.MEGABLOCKSURVIVORS, "megablocksurvivors", ConfigurationImporter::getMegaBlockSurvivorsPath);
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.MEGABLOCKSURVIVORS, "content_markers", importer -> importer.getMegaBlockSurvivorsPath().resolve("content_markers"));

        registerPlatformFolder(ConfigurationImporter.PluginPlatform.WORLDCANNON, "content_packages", importer -> importer.getWorldCannonPath().resolve("content_packages"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.WORLDCANNON, "cannonrtp", ConfigurationImporter::getWorldCannonPath);
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.WORLDCANNON, "worldcannon", ConfigurationImporter::getWorldCannonPath);
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.WORLDCANNON, "world_cannon", ConfigurationImporter::getWorldCannonPath);
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.WORLDCANNON, "fun_rtps", importer -> importer.getWorldCannonPath().resolve("cannons"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.WORLDCANNON, "cannons", importer -> importer.getWorldCannonPath().resolve("cannons"));

        registerPlatformFolder(ConfigurationImporter.PluginPlatform.FREEMINECRAFTMODELS,
                "content_packages", importer -> importer.getFreeMinecraftModelsPath().resolve("content_packages"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.FREEMINECRAFTMODELS,
                "scripts", importer -> importer.getFreeMinecraftModelsPath().resolve("scripts"));
        registerPlatformFolder(ConfigurationImporter.PluginPlatform.FREEMINECRAFTMODELS,
                "recipes", importer -> importer.getFreeMinecraftModelsPath().resolve("recipes"));

        registerPlatformFallback(ConfigurationImporter.PluginPlatform.FREEMINECRAFTMODELS,
                ConfigurationImportRegistry::resolveFmmModelsFolder);
    }

    private static void registerPlatformFolder(ConfigurationImporter.PluginPlatform platform,
                                               String folder,
                                               Function<ConfigurationImporter, Path> resolver) {
        platformResolvers.computeIfAbsent(platform, ignored -> new HashMap<>()).put(normalize(folder), resolver);
    }

    private static void registerGlobal(String folder, Function<ConfigurationImporter, Path> resolver) {
        globalResolvers.put(normalize(folder), resolver);
    }

    private static void registerSkipped(String folder) {
        skippedFolders.add(normalize(folder));
    }

    private static void registerPlatformFallback(ConfigurationImporter.PluginPlatform platform,
                                                 Function<ConfigurationImporter, Path> resolver) {
        platformFallbackResolvers.put(platform, resolver);
    }

    private static String normalize(String folder) {
        return folder.toLowerCase(Locale.ROOT);
    }
}
