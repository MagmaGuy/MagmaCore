package com.magmaguy.magmacore.thirdparty;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomBiomeCompatibility {
    private static final Map<Biome, List<Biome>> defaultBiomeToCustomBiomes = new HashMap<>();

    private CustomBiomeCompatibility() {
    }


    public static void shutdown() {
        defaultBiomeToCustomBiomes.clear();
    }

    /**
     * Initializes the mappings between default biomes and custom biomes.
     * Parses the provided mappings and populates the map.
     */
    public static void initializeMappings() {
        String mappings = """
                // Iris - The Dimensions Engine - Overworld Pack (default)
                    - iris:frozen_peak = minecraft:frozen_peaks
                    - iris:frozen_mountain = minecraft:frozen_peaks
                    - iris:frozen_mountaincliff = minecraft:frozen_peaks
                    - iris:frozen_mountain_middle = minecraft:frozen_peaks
                    - iris:frozen_hills = minecraft:snowy_slopes
                    - iris:frozen_pine_hills = minecraft:snowy_slopes
                    - iris:frozen_pine_plains = minecraft:snowy_slopes
                    - iris:frozen_pines = minecraft:snowy_taiga
                    - iris:frozen_plains = minecraft:snowy_plains
                    - iris:frozen_redwood_forest = minecraft:snowy_taiga
                    - iris:frozen_spruce_hills = minecraft:snowy_slopes
                    - iris:frozen_spruce_plains = minecraft:snowy_plains
                    - iris:frozen_river_ice = minecraft:frozen_river
                    - iris:frozen_beach = minecraft:snowy_beach
                    - iris:frozen_vander = minecraft:snowy_plains
                    - iris:hot_beach = minecraft:beach
                    - iris:mushroom_crimson_forest = minecraft:cherry_grove
                    - iris:mushroom_forest = minecraft:forest
                    - iris:mushroom_hills = minecraft:windswept_hills
                    - iris:mushroom_plains = minecraft:mushroom_fields
                    - iris:mushroom_warped_forest = minecraft:forest
                    - iris:mushroom_beach = minecraft:beach
                    - iris:savanna_acacia_denmyre = nothing
                    - iris:savanna_cliffs = minecraft:badlands
                    - iris:savanna_forest = minecraft:taiga
                    - iris:savanna_plateau = minecraft:savanna_plateau
                    - iris:savanna = minecraft:savanna
                    - iris:swamp_cambian_drift = minecraft:mangrove_swamp
                    - iris:swamp_marsh_rotten = minecraft:swamp
                    - iris:k530forestswamp = minecraft:mangrove_swamp
                    - iris:k530mangroveswamp = minecraft:mangrove_swamp
                    - iris:k530puddle = minecraft:swamp
                    - iris:island = nothing
                    - iris:longtree_forest = minecraft:old_growth_pine_taiga
                    - iris:tropical_highlands = minecraft:badlands
                    - iris:tropical_mountain_extreme = minecraft:eroded_badlands
                
                    // terra
                    - terra:carving_land = minecraft:eroded_badlands
                    - terra:carving_ocean = minecraft:deep_ocean
                    - terra:cave = minecraft:deep_dark
                    - terra:cold_deep_ocean = minecraft:cold_ocean
                    - terra:frozen_deep_ocean = minecraft:deep_frozen_ocean
                    - terra:iceberg_ocean = minecraft:deep_frozen_ocean
                    - terra:subtropical_deep_ocean = minecraft:deep_lukewarm_ocean
                    - terra:deep_ocean = minecraft:deep_lukewarm_ocean
                    - terra:tropical_deep_ocean = minecraft:deep_lukewarm_ocean
                    - terra:cold_ocean = minecraft:cold_ocean
                    - terra:frozen_ocean = minecraft:frozen_ocean
                    - terra:frozen_marsh = minecraft:frozen_river
                    - terra:frozen_river = minecraft:frozen_river
                    - terra:deep_dark = minecraft:deep_dark
                    - terra:dripstone_caves = minecraft:deep_dark
                    - terra:lush_caves = minecraft:lush_caves
                    - terra:autumnal_flats = minecraft:meadow
                    - terra:birch_flats = minecraft:birch_forest
                    - terra:taiga_flats = minecraft:taiga
                    - terra:yellowstone = minecraft:meadow
                    - terra:frozen_beach = minecraft:frozen_ocean
                    - terra:snowy_meadow = minecraft:snowy_plains
                    - terra:snowy_plains = minecraft:snowy_plains
                    - terra:tundra_plains = minecraft:badlands
                    - terra:evergreen_flats = minecraft:meadow
                    - terra:flowering_flats = minecraft:meadow
                    - terra:oak_savanna = minecraft:savanna
                    - terra:beach = minecraft:beach
                    - terra:shale_beach = minecraft:beach
                    - terra:shrub_beach = minecraft:beach
                    - terra:eucalyptus_forest = minecraft:forest
                    - terra:plains = minecraft:meadow
                    - terra:prairie = minecraft:savanna
                    - terra:steppe = minecraft:savanna_plateau
                    - terra:sunflower_plains = minecraft:meadow
                    - terra:forest_flats = minecraft:forest
                    - terra:rocky_archipelago = minecraft:stony_peaks
                    - terra:autumnal_forest_hills = minecraft:windswept_hills
                    - terra:birch_forest_hills = minecraft:windswept_hills
                    - terra:flowering_autumnal_forest_hills = minecraft:windswept_hills
                    - terra:redwood_forest_hills = minecraft:windswept_hills
                    - terra:taiga_hills = minecraft:windswept_forest
                    - terra:tundra_hills = minecraft:windswept_gravelly_hills
                    - terra:frozen_archipelago = minecraft:frozen_peaks
                    - terra:xerophytic_forest_hills = minecraft:windswept_forest
                    - terra:rainforest_hills = minecraft:windswept_forest
                    - terra:moorland = minecraft:windswept_forest
                    - terra:evergreen_forest_hills = minecraft:windswept_forest
                    - terra:flowering_forest_hills = minecraft:windswept_forest
                    - terra:archipelago = minecraft:windswept_hills
                    - terra:shrubland = minecraft:windswept_forest
                    - terra:dark_forest_hills = minecraft:windswept_forest
                    - terra:forest_hills = minecraft:windswept_forest
                    - terra:arid_spikes = minecraft:wooded_badlands
                    - terra:xeric_hills = minecraft:wooded_badlands
                    - terra:sandstone_archipelago = minecraft:wooded_badlands
                    - terra:bamboo_jungle_hills = minecraft:windswept_forest
                    - terra:jungle_hills = minecraft:windswept_forest
                    - terra:chaparral = minecraft:windswept_savanna
                    - terra:grass_savanna_hills = minecraft:windswept_savanna
                    - terra:savanna_hills = minecraft:windswept_savanna
                    - terra:rocky_sea_arches = minecraft:ocean
                    - terra:rocky_sea_caves = minecraft:ocean
                    - terra:snowy_sea_arches = minecraft:cold_ocean
                    - terra:snowy_sea_caves = minecraft:cold_ocean
                    - terra:snowy_terraced_mountains = minecraft:snowy_slopes
                    - terra:snowy_terraced_mountains_river = minecraft:snowy_beach
                    - terra:lush_sea_caves = minecraft:lush_caves
                    - terra:large_monsoon_mountains = minecraft:jagged_peaks
                    - terra:temperate_alpha_mountains = minecraft:jagged_peaks
                    - terra:temperate_sea_arches = minecraft:ocean
                    - terra:dry_temperate_mountains = minecraft:eroded_badlands
                    - terra:dry_temperate_mountains_river = minecraft:river
                    - terra:dry_temperate_white_mountains = minecraft:snowy_slopes
                    - terra:dry_temperate_white_mountains_river = minecraft:river
                    - terra:cracked_badlands_plateau = minecraft:jagged_peaks
                    - terra:terracotta_sea_arches = minecraft:ocean
                    - terra:terracotta_sea_caves = minecraft:ocean
                    - terra:bamboo_jungle_mountains = minecraft:wooded_badlands
                    - terra:jungle_mountains = minecraft:wooded_badlands
                    - terra:dry_wild_highlands = minecraft:eroded_badlands
                    - terra:cerros_de_mavecure = nothing
                    - terra:wild_highlands = minecraft:windswept_gravelly_hills
                    - terra:rocky_wetlands = minecraft:windswept_gravelly_hills
                    - terra:autumnal_forest = minecraft:windswept_savanna
                    - terra:birch_forest = minecraft:birch_forest
                    - terra:taiga = minecraft:taiga
                    - terra:frozen_wetlands = minecraft:frozen_peaks
                    - terra:ice_spikes = minecraft:ice_spikes
                    - terra:tundra_midlands = minecraft:badlands
                    - terra:xerophytic_forest = minecraft:forest
                    - terra:rainforest = minecraft:jungle
                    - terra:evergreen_forest = minecraft:old_growth_pine_taiga
                    - terra:flowering_forest = minecraft:old_growth_birch_forest
                    - terra:wetlands = minecraft:beach
                    - terra:dark_forest = minecraft:dark_forest
                    - terra:forest = minecraft:forest
                    - terra:wooded_buttes = minecraft:forest
                    - terra:badlands_buttes = minecraft:badlands
                    - terra:desert = minecraft:desert
                    - terra:desert_spikes = minecraft:desert
                    - terra:desert_spikes_gold = minecraft:desert
                    - terra:eroded_badlands_buttes = minecraft:eroded_badlands
                    - terra:rocky_desert = minecraft:badlands
                    - terra:sandstone_wetlands = minecraft:badlands
                    - terra:bamboo_jungle = minecraft:jungle
                    - terra:jungle = minecraft:jungle
                    - terra:low_chaparral = nothing
                    - terra:xeric_low_hills = minecraft:windswept_hills
                    - terra:grass_savanna_low_hills = minecraft:windswept_savanna
                    - terra:savanna_low_hills = minecraft:windswept_savanna
                    - terra:mountains = minecraft:windswept_hills
                    - terra:mountains_river = minecraft:windswept_hills
                    - terra:snowy_eroded_terraced_mountains = minecraft:snowy_slopes
                    - terra:snowy_eroded_terraced_mountains_river = minecraft:snowy_beach
                    - terra:snowy_mountains = minecraft:snowy_slopes
                    - terra:snowy_mountains_river = minecraft:snowy_beach
                    - terra:arid_highlands = minecraft:jagged_peaks
                    - terra:dry_rocky_bumpy_mountains = minecraft:windswept_gravelly_hills
                    - terra:monsoon_mountains = minecraft:windswept_gravelly_hills
                    - terra:rocky_bumpy_mountains = minecraft:windswept_gravelly_hills
                    - terra:evergreen_overhangs = minecraft:windswept_forest
                    - terra:wild_bumpy_mountains = minecraft:windswept_hills
                    - terra:highlands = minecraft:windswept_hills
                    - terra:sakura_mountains = minecraft:cherry_grove
                    - terra:temperate_mountains = minecraft:savanna_plateau
                    - terra:temperate_mountains_river = minecraft:savanna_plateau
                    - terra:arid_highlands = minecraft:savanna_plateau
                    - terra:badlands_mountains = minecraft:badlands
                    - terra:badlands_mountains_river = minecraft:badlands
                    - terra:desert_pillars = minecraft:desert
                    - terra:xeric_mountains = minecraft:windswept_hills
                    - terra:xeric_mountains_river = minecraft:windswept_hills
                    - terra:overgrown_cliffs = minecraft:windswept_forest
                    - terra:savanna_overhangs = minecraft:windswept_forest
                    - terra:mushroom_coast = minecraft:mushroom_fields
                    - terra:mushroom_fields = minecraft:mushroom_fields
                    - terra:mushroom_hills = minecraft:mushroom_fields
                    - terra:mushroom_mountains = minecraft:mushroom_fields
                    - terra:active_volcano_base = minecraft:eroded_badlands
                    - terra:active_volcano_base_edge = minecraft:badlands
                    - terra:active_volcano_pit = minecraft:eroded_badlands
                    - terra:active_volcano_pit_edge = minecraft:badlands
                    - terra:caldera_volcano_base = minecraft:eroded_badlands
                    - terra:caldera_volcano_base_edge = minecraft:badlands
                    - terra:caldera_volcano_pit = minecraft:eroded_badlands
                    - terra:caldera_volcano_pit_edge = minecraft:badlands
                
                    // terraform generator
                    - terraformgenerator:snowy_mountains = minecraft:frozen_peaks
                    - terraformgenerator:birch_mountains = minecraft:windswept_forest
                    - terraformgenerator:rocky_mountains = minecraft:stony_peaks
                    - terraformgenerator:forested_mountains = minecraft:stony_peaks
                    - terraformgenerator:shattered_savanna = minecraft:savanna
                    - terraformgenerator:painted_hills = nothing
                    - terraformgenerator:badlands_canyon = minecraft:badlands
                    - terraformgenerator:desert_mountains = nothing
                    - terraformgenerator:jagged_peaks = minecraft:jagged_peaks
                    - terraformgenerator:cold_jagged_peaks = minecraft:jagged_peaks
                    - terraformgenerator:transition_jagged_peaks = minecraft:jagged_peaks
                    - terraformgenerator:forested_peaks = minecraft:stony_peaks
                    - terraformgenerator:shattered_savanna_peak = minecraft:savanna_plateau
                    - terraformgenerator:badlands_canyon_peak = minecraft:eroded_badlands
                    - terraformgenerator:ocean = minecraft:ocean
                    - terraformgenerator:black_ocean = minecraft:ocean
                    - terraformgenerator:cold_ocean = minecraft:cold_ocean
                    - terraformgenerator:frozen_ocean = minecraft:frozen_ocean
                    - terraformgenerator:warm_ocean = minecraft:warm_ocean
                    - terraformgenerator:humid_ocean = minecraft:lukewarm_ocean
                    - terraformgenerator:dry_ocean = minecraft:ocean
                    - terraformgenerator:coral_reef_ocean = minecraft:deep_ocean
                    - terraformgenerator:river = minecraft:river
                    - terraformgenerator:bog_river = minecraft:river
                    - terraformgenerator:cherry_grove_river = minecraft:river
                    - terraformgenerator:scarlet_forest_river = minecraft:river
                    - terraformgenerator:jungle_river = minecraft:river
                    - terraformgenerator:frozen_river = minecraft:frozen_river
                    - terraformgenerator:dark_forest_river = minecraft:river
                    - terraformgenerator:desert_river = minecraft:river
                    - terraformgenerator:badlands_river = minecraft:river
                    - terraformgenerator:deep_ocean = minecraft:deep_ocean
                    - terraformgenerator:deep_cold_ocean = minecraft:deep_cold_ocean
                    - terraformgenerator:deep_black_ocean = minecraft:deep_ocean
                    - terraformgenerator:deep_frozen_ocean = minecraft:deep_frozen_ocean
                    - terraformgenerator:deep_warm_ocean = minecraft:deep_warm_ocean
                    - terraformgenerator:deep_humid_ocean = minecraft:deep_lukewarm_ocean
                    - terraformgenerator:deep_dry_ocean = minecraft:deep_ocean
                    - terraformgenerator:deep_lukewarm_ocean = minecraft:deep_lukewarm_ocean
                    - terraformgenerator:mushroom_islands = minecraft:mushroom_fields
                    - terraformgenerator:plains = minecraft:plains
                    - terraformgenerator:elevated_plains = minecraft:windswept_hills
                    - terraformgenerator:dodge_petrified_cliffs = nothing
                    - terraformgenerator:arched_cliffs = nothing
                    - terraformgenerator:savanna_muddy_bog_forest = nothing
                    - terraformgenerator:jungle = minecraft:jungle
                    - terraformgenerator:bamboo_forest = minecraft:bamboo_jungle
                    - terraformgenerator:desert_badlands = minecraft:desert
                    - terraformgenerator:eroded_plains = minecraft:plains
                    - terraformgenerator:scarlet_forest = nothing
                    - terraformgenerator:cherry_grove = minecraft:cherry_grove
                    - terraformgenerator:taiga = minecraft:taiga
                    - terraformgenerator:snowy_taiga = minecraft:snowy_taiga
                    - terraformgenerator:snowy_wasteland = minecraft:ice_spikes
                    - terraformgenerator:ice_spikes = minecraft:ice_spikes
                    - terraformgenerator:dark_forest = minecraft:dark_forest
                    - terraformgenerator:swamp = minecraft:swamp
                    - terraformgenerator:mangrove = minecraft:mangrove_swamp
                    - terraformgenerator:sandy_beach = minecraft:beach
                    - terraformgenerator:bog_beach = nothing
                    - terraformgenerator:dark_forest_beach = minecraft:beach
                    - terraformgenerator:badlands_beach = minecraft:beach
                    - terraformgenerator:mushroom_beach = minecraft:beach
                    - terraformgenerator:black_ocean_beach = minecraft:beach
                    - terraformgenerator:rocky_beach = minecraft:stony_shore
                    - terraformgenerator:icy_beach = minecraft:snowy_beach
                    - terraformgenerator:mud_flats = nothing
                    - terraformgenerator:cherry_grove_beach = minecraft:beach
                    - terraformgenerator:scarlet_forest_beach = minecraft:beach
                
                
                    // terralith
                    - terralith:alpha_islands = minecraft:plains
                    - terralith:alpha_islands_winter = minecraft:snowy_plains
                    - terralith:alpine_grove = minecraft:grove
                    - terralith:alpine_highlands = minecraft:plains
                    - terralith:amethyst_canyon = minecraft:forest
                    - terralith:amethyst_rainforest = minecraft:jungle
                    - terralith:ancient_sands = minecraft:desert
                    - terralith:arid_highlands = minecraft:savanna
                    - terralith:ashen_savanna = minecraft:savanna
                    - terralith:basalt_cliffs = minecraft:basalt_deltas
                    - terralith:birch_taiga = minecraft:birch_forest
                    - terralith:blooming_plateau = minecraft:meadow
                    - terralith:blooming_valley = minecraft:flower_forest
                    - terralith:brushland = minecraft:plains
                    - terralith:bryce_canyon = minecraft:eroded_badlands
                    - terralith:caldera = minecraft:badlands
                    - terralith:cloud_forest = minecraft:flower_forest
                    - terralith:cold_shrubland = minecraft:snowy_plains
                    - terralith:desert_canyon = minecraft:desert
                    - terralith:desert_oasis = minecraft:desert
                    - terralith:desert_spires = minecraft:desert
                    - terralith:emerald_peaks = minecraft:stony_peaks
                    - terralith:forested_highlands = minecraft:taiga
                    - terralith:fractured_savanna = minecraft:windswept_savanna
                    - terralith:frozen_cliffs = minecraft:ice_spikes
                    - terralith:glacial_chasm = minecraft:ice_spikes
                    - terralith:granite_cliffs = minecraft:stony_shore
                    - terralith:gravel_beach = minecraft:stony_shore
                    - terralith:gravel_desert = minecraft:desert
                    - terralith:haze_mountain = minecraft:windswept_forest
                    - terralith:highlands = minecraft:plains
                    - terralith:hot_shrubland = minecraft:wooded_badlands
                    - terralith:ice_marsh = minecraft:swamp
                    - terralith:jungle_mountains = minecraft:jungle
                    - terralith:lavender_forest = minecraft:cherry_grove
                    - terralith:lavender_valley = minecraft:cherry_grove
                    - terralith:lush_desert = minecraft:desert
                    - terralith:lush_valley = minecraft:taiga
                    - terralith:mirage_isles = nothing
                    - terralith:moonlight_grove = minecraft:forest
                    - terralith:moonlight_valley = minecraft:forest
                    - terralith:orchid_swamp = minecraft:mangrove_swamp
                    - terralith:painted_mountains = minecraft:eroded_badlands
                    - terralith:red_oasis = minecraft:wooded_badlands
                    - terralith:rocky_jungle = minecraft:jungle
                    - terralith:rocky_mountains = minecraft:frozen_peaks
                    - terralith:rocky_shrubland = minecraft:snowy_plains
                    - terralith:sakura_grove = minecraft:cherry_grove
                    - terralith:sakura_valley = minecraft:cherry_grove
                    - terralith:sandstone_valley = minecraft:desert
                    - terralith:savanna_badlands = minecraft:windswept_savanna
                    - terralith:savanna_slopes = minecraft:windswept_savanna
                    - terralith:scarlet_mountains = minecraft:snowy_taiga
                    - terralith:shield_clearing = minecraft:windswept_gravelly_hills
                    - terralith:shield = minecraft:old_growth_spruce_taiga
                    - terralith:shrubland = minecraft:windswept_savanna
                    - terralith:siberian_grove = minecraft:grove
                    - terralith:siberian_taiga = minecraft:snowy_taiga
                    - terralith:skylands = minecraft:forest
                    - terralith:skylands_autumn = minecraft:forest
                    - terralith:skylands_spring = minecraft:forest
                    - terralith:skylands_summer = minecraft:forest
                    - terralith:skylands_winter = minecraft:forest
                    - terralith:snowy_badlands = minecraft:snowy_slopes
                    - terralith:snowy_cherry_grove = minecraft:cherry_grove
                    - terralith:snowy_maple_forest = minecraft:birch_forest
                    - terralith:snowy_shield = minecraft:snowy_taiga
                    - terralith:steppe = minecraft:plains
                    - terralith:stony_spires = minecraft:jagged_peaks
                    - terralith:temperate_highlands = minecraft:plains
                    - terralith:tropical_jungle = minecraft:jungle
                    - terralith:valley_clearing = minecraft:plains
                    - terralith:volcanic_crater = minecraft:badlands
                    - terralith:volcanic_peaks = minecraft:badlands
                    - terralith:warm_river = minecraft:river
                    - terralith:warped_mesa = minecraft:eroded_badlands
                    - terralith:white_cliffs = minecraft:badlands
                    - terralith:white_mesa = minecraft:eroded_badlands
                    - terralith:windswept_spires = minecraft:jagged_peaks
                    - terralith:wintry_forest = minecraft:grove
                    - terralith:wintry_lowlands = minecraft:grove
                    - terralith:yellowstone = minecraft:badlands
                    - terralith:yosemite_cliffs = minecraft:forest
                    - terralith:yosemite_lowlands = minecraft:old_growth_pine_taiga
                    - terralith:cave/andesite_caves = minecraft:dripstone_caves
                    - terralith:cave/desert_caves = minecraft:dripstone_caves
                    - terralith:cave/diorite_caves = minecraft:dripstone_caves
                    - terralith:cave/fungal_caves = minecraft:dripstone_caves
                    - terralith:cave/granite_caves = minecraft:dripstone_caves
                    - terralith:cave/ice_caves = minecraft:dripstone_caves
                    - terralith:cave/infested_caves = minecraft:dripstone_caves
                    - terralith:cave/thermal_caves = minecraft:dripstone_caves
                    - terralith:cave/underground_jungle = minecraft:dripstone_caves
                    - terralith:cave/crystal_caves = minecraft:dripstone_caves
                    - terralith:cave/deep_caves = minecraft:dripstone_caves
                    - terralith:cave/frostfire_caves = minecraft:dripstone_caves
                    - terralith:cave/mantle_caves = minecraft:dripstone_caves
                    - terralith:cave/tuff_caves = minecraft:dripstone_caves
                """;

        // Regular expression pattern to match each mapping line
        Pattern pattern = Pattern.compile("-\\s*([^:]+):(.*?)\\s*=\\s*(Minecraft):(.*?)$", Pattern.CASE_INSENSITIVE);

        // Split the mappings string into individual lines
        String[] lines = mappings.split("\\r?\\n");

        for (String line : lines) {
            line = line.trim();
            if (line.isEmpty() || line.startsWith("//")) continue;

            // Handle lines that match the pattern
            Matcher matcher = pattern.matcher(line);
            if (matcher.matches()) {
                String pluginName = matcher.group(1).trim();
                String customBiomeName = matcher.group(2).trim().toUpperCase();
                String defaultBiomeName = matcher.group(4).trim().toUpperCase();

                if (defaultBiomeName.equalsIgnoreCase("nothing")) continue;

                Biome defaultBiome;
                try {
//                    defaultBiome = Biome.valueOf(defaultBiomeName);
                    defaultBiome = Registry.BIOME.get(new NamespacedKey("minecraft", defaultBiomeName));
                } catch (IllegalArgumentException e) {
                    // Default biome not found
                    continue;
                }

                Biome customBiome = Registry.BIOME.get(new NamespacedKey(pluginName.toLowerCase(), customBiomeName.toLowerCase()));
                if (customBiome == null) {
//                    Logger.warn("Could not find biome " + customBiomeName + " in registry for plugin " + pluginName + " (line: " + line + ")");
                    // Custom biome not found in registry
                    continue;
                } else {
                    Logger.info("Successfully loaded custom biome " + customBiomeName + " for plugin " + pluginName + " (line: " + line + ")");
                }

                defaultBiomeToCustomBiomes.computeIfAbsent(defaultBiome, k -> new ArrayList<>()).add(customBiome);
            } else {
                // Handle lines without plugin name
                if (line.contains("nothing")) continue; // Skip lines mapping to 'nothing'
                if (line.contains("=")) {
                    String[] parts = line.split("=");
                    if (parts.length == 2) {
                        String customBiomePart = parts[0].replaceFirst("-\\s*", "").trim();
                        String defaultBiomePart = parts[1].trim();

                        String customBiomeName = customBiomePart.toUpperCase();
                        String defaultBiomeName = defaultBiomePart.replace("minecraft:", "").trim().toUpperCase();

                        if (defaultBiomeName.equalsIgnoreCase("NOTHING")) continue;

                        Biome defaultBiome;
                        try {
//                            defaultBiome = Biome.valueOf(defaultBiomeName);
                            defaultBiome = Registry.BIOME.get(new NamespacedKey("minecraft", defaultBiomeName));
                        } catch (IllegalArgumentException e) {
                            // Default biome not found
                            continue;
                        }

                        Biome customBiome;
                        try {
                            customBiome = Biome.valueOf(customBiomeName);
                        } catch (IllegalArgumentException e) {
                            // Custom biome not found in Biome enum
                            // Attempt to get it from the registry with "minecraft" namespace
                            customBiome = Registry.BIOME.get(new NamespacedKey("minecraft", customBiomeName.toLowerCase()));
                            if (customBiome == null) {
                                // Custom biome not found in registry
                                continue;
                            }
                        }

                        defaultBiomeToCustomBiomes.computeIfAbsent(defaultBiome, k -> new ArrayList<>()).add(customBiome);
                    }
                }
            }
        }
    }

    /**
     * Retrieves the list of custom biomes associated with the given default biome.
     *
     * @param defaultBiome The default Minecraft biome.
     * @return A list of custom biomes mapping to the default biome.
     */
    public static List<Biome> getCustomBiomes(Biome defaultBiome) {
        if (defaultBiome == null) {
            return Collections.emptyList();
        }
        return defaultBiomeToCustomBiomes.getOrDefault(defaultBiome, Collections.emptyList());
    }
}
