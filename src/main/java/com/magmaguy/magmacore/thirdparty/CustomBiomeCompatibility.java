package com.magmaguy.magmacore.thirdparty;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.block.Biome;

import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public class CustomBiomeCompatibility {
    // Changed from Map<Biome, List<Biome>> to Map<String, List<String>>
    private static final Map<String, List<String>> defaultBiomeToCustomBiomes = new HashMap<>();

    private CustomBiomeCompatibility() {
    }

    public static void shutdown() {
        defaultBiomeToCustomBiomes.clear();
    }

    /**
     * Initializes the mappings between default biomes and custom biomes.
     * Parses the provided mappings and populates the map using String identifiers
     * in the format "namespace:key".
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
                    - terra:overworld/overworld/carving_land = minecraft:eroded_badlands
                    - terra:overworld/overworld/carving_ocean = minecraft:deep_ocean
                    - terra:overworld/overworld/cave = minecraft:deep_dark
                    - terra:overworld/overworld/cold_deep_ocean = minecraft:cold_ocean
                    - terra:overworld/overworld/frozen_deep_ocean = minecraft:deep_frozen_ocean
                    - terra:overworld/overworld/iceberg_ocean = minecraft:deep_frozen_ocean
                    - terra:overworld/overworld/subtropical_deep_ocean = minecraft:deep_lukewarm_ocean
                    - terra:overworld/overworld/deep_ocean = minecraft:deep_lukewarm_ocean
                    - terra:overworld/overworld/tropical_deep_ocean = minecraft:deep_lukewarm_ocean
                    - terra:overworld/overworld/cold_ocean = minecraft:cold_ocean
                    - terra:overworld/overworld/frozen_ocean = minecraft:frozen_ocean
                    - terra:overworld/overworld/frozen_marsh = minecraft:frozen_river
                    - terra:overworld/overworld/frozen_river = minecraft:frozen_river
                    - terra:overworld/overworld/deep_dark = minecraft:deep_dark
                    - terra:overworld/overworld/dripstone_caves = minecraft:deep_dark
                    - terra:overworld/overworld/lush_caves = minecraft:lush_caves
                    - terra:overworld/overworld/autumnal_flats = minecraft:meadow
                    - terra:overworld/overworld/birch_flats = minecraft:birch_forest
                    - terra:overworld/overworld/taiga_flats = minecraft:taiga
                    - terra:overworld/overworld/yellowstone = minecraft:meadow
                    - terra:overworld/overworld/frozen_beach = minecraft:frozen_ocean
                    - terra:overworld/overworld/snowy_meadow = minecraft:snowy_plains
                    - terra:overworld/overworld/snowy_plains = minecraft:snowy_plains
                    - terra:overworld/overworld/tundra_plains = minecraft:badlands
                    - terra:overworld/overworld/evergreen_flats = minecraft:meadow
                    - terra:overworld/overworld/flowering_flats = minecraft:meadow
                    - terra:overworld/overworld/oak_savanna = minecraft:savanna
                    - terra:overworld/overworld/beach = minecraft:beach
                    - terra:overworld/overworld/shale_beach = minecraft:beach
                    - terra:overworld/overworld/shrub_beach = minecraft:beach
                    - terra:overworld/overworld/eucalyptus_forest = minecraft:forest
                    - terra:overworld/overworld/plains = minecraft:meadow
                    - terra:overworld/overworld/prairie = minecraft:savanna
                    - terra:overworld/overworld/steppe = minecraft:savanna_plateau
                    - terra:overworld/overworld/sunflower_plains = minecraft:meadow
                    - terra:overworld/overworld/forest_flats = minecraft:forest
                    - terra:overworld/overworld/rocky_archipelago = minecraft:stony_peaks
                    - terra:overworld/overworld/autumnal_forest_hills = minecraft:windswept_hills
                    - terra:overworld/overworld/birch_forest_hills = minecraft:windswept_hills
                    - terra:overworld/overworld/flowering_autumnal_forest_hills = minecraft:windswept_hills
                    - terra:overworld/overworld/redwood_forest_hills = minecraft:windswept_hills
                    - terra:overworld/overworld/taiga_hills = minecraft:windswept_forest
                    - terra:overworld/overworld/tundra_hills = minecraft:windswept_gravelly_hills
                    - terra:overworld/overworld/frozen_archipelago = minecraft:frozen_peaks
                    - terra:overworld/overworld/xerophytic_forest_hills = minecraft:windswept_forest
                    - terra:overworld/overworld/rainforest_hills = minecraft:windswept_forest
                    - terra:overworld/overworld/moorland = minecraft:windswept_forest
                    - terra:overworld/overworld/evergreen_forest_hills = minecraft:windswept_forest
                    - terra:overworld/overworld/flowering_forest_hills = minecraft:windswept_forest
                    - terra:overworld/overworld/archipelago = minecraft:windswept_hills
                    - terra:overworld/overworld/shrubland = minecraft:windswept_forest
                    - terra:overworld/overworld/dark_forest_hills = minecraft:windswept_forest
                    - terra:overworld/overworld/forest_hills = minecraft:windswept_forest
                    - terra:overworld/overworld/arid_spikes = minecraft:wooded_badlands
                    - terra:overworld/overworld/xeric_hills = minecraft:wooded_badlands
                    - terra:overworld/overworld/sandstone_archipelago = minecraft:wooded_badlands
                    - terra:overworld/overworld/bamboo_jungle_hills = minecraft:windswept_forest
                    - terra:overworld/overworld/jungle_hills = minecraft:windswept_forest
                    - terra:overworld/overworld/chaparral = minecraft:windswept_savanna
                    - terra:overworld/overworld/grass_savanna_hills = minecraft:windswept_savanna
                    - terra:overworld/overworld/savanna_hills = minecraft:windswept_savanna
                    - terra:overworld/overworld/rocky_sea_arches = minecraft:ocean
                    - terra:overworld/overworld/rocky_sea_caves = minecraft:ocean
                    - terra:overworld/overworld/snowy_sea_arches = minecraft:cold_ocean
                    - terra:overworld/overworld/snowy_sea_caves = minecraft:cold_ocean
                    - terra:overworld/overworld/snowy_terraced_mountains = minecraft:snowy_slopes
                    - terra:overworld/overworld/snowy_terraced_mountains_river = minecraft:snowy_beach
                    - terra:overworld/overworld/lush_sea_caves = minecraft:lush_caves
                    - terra:overworld/overworld/large_monsoon_mountains = minecraft:jagged_peaks
                    - terra:overworld/overworld/temperate_alpha_mountains = minecraft:jagged_peaks
                    - terra:overworld/overworld/temperate_sea_arches = minecraft:ocean
                    - terra:overworld/overworld/dry_temperate_mountains = minecraft:eroded_badlands
                    - terra:overworld/overworld/dry_temperate_mountains_river = minecraft:river
                    - terra:overworld/overworld/dry_temperate_white_mountains = minecraft:snowy_slopes
                    - terra:overworld/overworld/dry_temperate_white_mountains_river = minecraft:river
                    - terra:overworld/overworld/cracked_badlands_plateau = minecraft:jagged_peaks
                    - terra:overworld/overworld/terracotta_sea_arches = minecraft:ocean
                    - terra:overworld/overworld/terracotta_sea_caves = minecraft:ocean
                    - terra:overworld/overworld/bamboo_jungle_mountains = minecraft:wooded_badlands
                    - terra:overworld/overworld/jungle_mountains = minecraft:wooded_badlands
                    - terra:overworld/overworld/dry_wild_highlands = minecraft:eroded_badlands
                    - terra:overworld/overworld/cerros_de_mavecure = nothing
                    - terra:overworld/overworld/wild_highlands = minecraft:windswept_gravelly_hills
                    - terra:overworld/overworld/rocky_wetlands = minecraft:windswept_gravelly_hills
                    - terra:overworld/overworld/autumnal_forest = minecraft:windswept_savanna
                    - terra:overworld/overworld/birch_forest = minecraft:birch_forest
                    - terra:overworld/overworld/taiga = minecraft:taiga
                    - terra:overworld/overworld/frozen_wetlands = minecraft:frozen_peaks
                    - terra:overworld/overworld/ice_spikes = minecraft:ice_spikes
                    - terra:overworld/overworld/tundra_midlands = minecraft:badlands
                    - terra:overworld/overworld/xerophytic_forest = minecraft:forest
                    - terra:overworld/overworld/rainforest = minecraft:jungle
                    - terra:overworld/overworld/evergreen_forest = minecraft:old_growth_pine_taiga
                    - terra:overworld/overworld/flowering_forest = minecraft:old_growth_birch_forest
                    - terra:overworld/overworld/wetlands = minecraft:beach
                    - terra:overworld/overworld/dark_forest = minecraft:dark_forest
                    - terra:overworld/overworld/forest = minecraft:forest
                    - terra:overworld/overworld/wooded_buttes = minecraft:forest
                    - terra:overworld/overworld/badlands_buttes = minecraft:badlands
                    - terra:overworld/overworld/desert = minecraft:desert
                    - terra:overworld/overworld/desert_spikes = minecraft:desert
                    - terra:overworld/overworld/desert_spikes_gold = minecraft:desert
                    - terra:overworld/overworld/eroded_badlands_buttes = minecraft:eroded_badlands
                    - terra:overworld/overworld/rocky_desert = minecraft:badlands
                    - terra:overworld/overworld/sandstone_wetlands = minecraft:badlands
                    - terra:overworld/overworld/bamboo_jungle = minecraft:jungle
                    - terra:overworld/overworld/jungle = minecraft:jungle
                    - terra:overworld/overworld/low_chaparral = nothing
                    - terra:overworld/overworld/xeric_low_hills = minecraft:windswept_hills
                    - terra:overworld/overworld/grass_savanna_low_hills = minecraft:windswept_savanna
                    - terra:overworld/overworld/savanna_low_hills = minecraft:windswept_savanna
                    - terra:overworld/overworld/mountains = minecraft:windswept_hills
                    - terra:overworld/overworld/mountains_river = minecraft:windswept_hills
                    - terra:overworld/overworld/snowy_eroded_terraced_mountains = minecraft:snowy_slopes
                    - terra:overworld/overworld/snowy_eroded_terraced_mountains_river = minecraft:snowy_beach
                    - terra:overworld/overworld/snowy_mountains = minecraft:snowy_slopes
                    - terra:overworld/overworld/snowy_mountains_river = minecraft:snowy_beach
                    - terra:overworld/overworld/arid_highlands = minecraft:jagged_peaks
                    - terra:overworld/overworld/dry_rocky_bumpy_mountains = minecraft:windswept_gravelly_hills
                    - terra:overworld/overworld/monsoon_mountains = minecraft:windswept_gravelly_hills
                    - terra:overworld/overworld/rocky_bumpy_mountains = minecraft:windswept_gravelly_hills
                    - terra:overworld/overworld/evergreen_overhangs = minecraft:windswept_forest
                    - terra:overworld/overworld/wild_bumpy_mountains = minecraft:windswept_hills
                    - terra:overworld/overworld/highlands = minecraft:windswept_hills
                    - terra:overworld/overworld/sakura_mountains = minecraft:cherry_grove
                    - terra:overworld/overworld/temperate_mountains = minecraft:savanna_plateau
                    - terra:overworld/overworld/temperate_mountains_river = minecraft:savanna_plateau
                    - terra:overworld/overworld/arid_highlands = minecraft:savanna_plateau
                    - terra:overworld/overworld/badlands_mountains = minecraft:badlands
                    - terra:overworld/overworld/badlands_mountains_river = minecraft:badlands
                    - terra:overworld/overworld/desert_pillars = minecraft:desert
                    - terra:overworld/overworld/xeric_mountains = minecraft:windswept_hills
                    - terra:overworld/overworld/xeric_mountains_river = minecraft:windswept_hills
                    - terra:overworld/overworld/overgrown_cliffs = minecraft:windswept_forest
                    - terra:overworld/overworld/savanna_overhangs = minecraft:windswept_forest
                    - terra:overworld/overworld/mushroom_coast = minecraft:mushroom_fields
                    - terra:overworld/overworld/mushroom_fields = minecraft:mushroom_fields
                    - terra:overworld/overworld/mushroom_hills = minecraft:mushroom_fields
                    - terra:overworld/overworld/mushroom_mountains = minecraft:mushroom_fields
                    - terra:overworld/overworld/active_volcano_base = minecraft:eroded_badlands
                    - terra:overworld/overworld/active_volcano_base_edge = minecraft:badlands
                    - terra:overworld/overworld/active_volcano_pit = minecraft:eroded_badlands
                    - terra:overworld/overworld/active_volcano_pit_edge = minecraft:badlands
                    - terra:overworld/overworld/caldera_volcano_base = minecraft:eroded_badlands
                    - terra:overworld/overworld/caldera_volcano_base_edge = minecraft:badlands
                    - terra:overworld/overworld/caldera_volcano_pit = minecraft:eroded_badlands
                    - terra:overworld/overworld/caldera_volcano_pit_edge = minecraft:badlands
                
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
                String pluginName = matcher.group(1).trim().toLowerCase();
                String customBiomeKey = matcher.group(2).trim().toLowerCase();
                String defaultBiomeKey = matcher.group(4).trim().toLowerCase();

                if (defaultBiomeKey.equalsIgnoreCase("nothing")) continue;

                // Create formatted string identifiers
                String defaultBiomeId = "minecraft:" + defaultBiomeKey;
                String customBiomeId = pluginName + ":" + customBiomeKey;

                // Store mapping
                defaultBiomeToCustomBiomes.computeIfAbsent(defaultBiomeId, k -> new ArrayList<>()).add(customBiomeId);
//                Logger.info("Successfully mapped custom biome " + customBiomeId + " to default biome " + defaultBiomeId);
            } else {
                // Handle lines without the full pattern
                if (line.contains("nothing")) continue; // Skip lines mapping to 'nothing'
                if (line.contains("=")) {
                    String[] parts = line.split("=");
                    if (parts.length == 2) {
                        String customBiomePart = parts[0].replaceFirst("-\\s*", "").trim();
                        String defaultBiomePart = parts[1].trim();

                        if (defaultBiomePart.equalsIgnoreCase("NOTHING")) continue;

                        // Format the string identifiers, ensuring they're in lowercase
                        String customBiomeId = customBiomePart.toLowerCase();
                        String defaultBiomeId = defaultBiomePart.replace("minecraft:", "").trim();

                        // Make sure the default biome has the minecraft namespace
                        if (!defaultBiomeId.contains(":")) {
                            defaultBiomeId = "minecraft:" + defaultBiomeId.toLowerCase();
                        } else {
                            defaultBiomeId = defaultBiomeId.toLowerCase();
                        }

                        // Store mapping
                        defaultBiomeToCustomBiomes.computeIfAbsent(defaultBiomeId, k -> new ArrayList<>()).add(customBiomeId);
//                        Logger.info("Successfully mapped custom biome " + customBiomeId + " to default biome " + defaultBiomeId);
                    }
                }
            }
        }
    }

    /**
     * Retrieves the list of custom biomes associated with the given default biome.
     *
     * @param defaultBiomeId The default Minecraft biome identifier in "namespace:key" format.
     * @return A list of custom biome identifiers mapping to the default biome.
     */
    public static List<String> getCustomBiomes(String defaultBiomeId) {
        if (defaultBiomeId == null || defaultBiomeId.isEmpty()) {
            return Collections.emptyList();
        }
        return defaultBiomeToCustomBiomes.getOrDefault(defaultBiomeId.toLowerCase(), Collections.emptyList());
    }

    /**
     * Retrieves the list of custom biomes associated with the given default biome.
     * This is a convenience method that accepts a Biome object and converts it to the string format.
     *
     * @param defaultBiome The default Minecraft biome.
     * @return A list of custom biome identifiers mapping to the default biome.
     */
    public static List<String> getCustomBiomes(Biome defaultBiome) {
        if (defaultBiome == null) {
            return Collections.emptyList();
        }

        NamespacedKey key = defaultBiome.getKey();
        String defaultBiomeId = key.getNamespace() + ":" + key.getKey();
        return getCustomBiomes(defaultBiomeId);
    }
}