package com.magmaguy.magmacore.dlc;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.events.ModelInstallationEvent;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;

public class ConfigurationImporter {
    private final Path eliteMobsPath = Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getParentFile().getAbsolutePath()).resolve("EliteMobs");
    private final Path extractioncraftPath = Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getParentFile().getAbsolutePath()).resolve("Extractioncraft");
    private final Path betterStructuresPath = Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getParentFile().getAbsolutePath()).resolve("BetterStructures");
    private final Path freeMinecraftModelsPath = Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getParentFile().getAbsolutePath()).resolve("FreeMinecraftModels");
    private final Path modelEnginePath = Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getParentFile().getAbsolutePath()).resolve("ModelEngine");
    private final Path eternalTDPath = Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getParentFile().getAbsolutePath()).resolve("EternalTD");
    private PluginPlatform pluginPlatform;
    private File importsFolder;
    private boolean modelsInstalled = false;

    public ConfigurationImporter() {
        if (!createImportsDirectory()) return;
        importsFolder = getImportsDirectory();
        if (importsFolder == null || importsFolder.listFiles().length == 0) return;
        pluginPlatform = getPluginPlatform(MagmaCore.getInstance().getRequestingPlugin().getName());
        processImportsFolder();
        if (Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels"))
            if (modelsInstalled) Bukkit.getPluginManager().callEvent(new ModelInstallationEvent());
    }

    private static void deleteDirectory(File file) {
        if (file == null) return;
        if (file.isDirectory()) {
            for (File iteratedFile : file.listFiles()) {
                if (iteratedFile != null) deleteDirectory(iteratedFile);
            }
        }
        Logger.info("Cleaning up " + file.getPath());
        file.delete();
    }

    private static void moveWorlds(File worldcontainerFile) {
        for (File file : worldcontainerFile.listFiles()) {
            try {
                File worldContainer = Bukkit.getWorldContainer().getCanonicalFile();
                Path worldContainerPath = worldContainer.toPath().normalize().toAbsolutePath();
                Path destinationPath = worldContainerPath.resolve(file.getName());
                File destinationFile = destinationPath.toFile();

                if (destinationFile.exists()) {
                    Logger.info("Overriding existing directory " + destinationFile.getPath());
                    if (Bukkit.getWorld(file.getName()) != null) {
                        Bukkit.unloadWorld(file.getName(), false);
                        Logger.warn("Unloaded world " + file.getName() + " for safe replacement!");
                    }
                    deleteDirectory(destinationFile);
                }
                moveDirectory(file, destinationPath);
            } catch (Exception exception) {
                Logger.warn("Failed to move worlds for " + file.getName() + "! Tell the dev!");
                exception.printStackTrace();
            }
        }
    }

    private static void moveDirectory(File unzippedDirectory, Path targetPath) {
        for (File file : unzippedDirectory.listFiles()) {
            try {
                moveFile(file, targetPath);
            } catch (Exception exception) {
                Logger.warn("Failed to move directories for " + file.getName() + "! Tell the dev!");
                exception.printStackTrace();
            }
        }
    }

    private static void moveFile(File file, Path targetPath) {
        try {
            Path destinationPath = targetPath.resolve(file.getName());
            if (file.isDirectory()) {
                if (Files.exists(destinationPath)) {
                    for (File iteratedFile : file.listFiles()) {
                        moveFile(iteratedFile, destinationPath);
                    }
                } else {
                    Files.createDirectories(targetPath);
                    Files.move(file.toPath().normalize().toAbsolutePath(), destinationPath, StandardCopyOption.REPLACE_EXISTING);
                }
            } else {
                Files.createDirectories(targetPath);
                Files.move(file.toPath().normalize().toAbsolutePath(), destinationPath, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            Logger.warn("Failed to move file/directories for " + file.getName() + "! Tell the dev!");
            exception.printStackTrace();
        }
    }

    private boolean createImportsDirectory() {
        Path configurationsPath = Paths.get(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getAbsolutePath());
        Path importsPath = configurationsPath.normalize().resolve("imports");
        if (!Files.isDirectory(importsPath)) {
            try {
                File importsFile = importsPath.toFile();
                if (!importsFile.getParentFile().exists())
                    importsPath.toFile().mkdirs();
                Files.createDirectories(importsPath);
                return true;
            } catch (Exception exception) {
                Logger.warn("Failed to create import directory! Tell the dev!");
                exception.printStackTrace();
                return false;
            }
        }
        return true;
    }

    private File getImportsDirectory() {
        try {
            File dir = Paths.get(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getCanonicalPath()).resolve("imports").toFile();
            return dir;
        } catch (Exception ex) {
            Logger.warn("Failed to get imports folder! Report this to the dev!");
            ex.printStackTrace();
            return null;
        }
    }

    private PluginPlatform getPluginPlatform(String name) {
        if (name == null) return PluginPlatform.NONE;
        switch (name.toLowerCase(Locale.ROOT)) {
            case "elitemobs":
                return PluginPlatform.ELITEMOBS;
            case "extractioncraft":
                return PluginPlatform.EXTRACTIONCRAFT;
            case "betterstructures":
                return PluginPlatform.BETTERSTRUCTURES;
            case "freeminecraftmodels":
                return PluginPlatform.FREEMINECRAFTMODELS;
            case "eternaltd":
                return PluginPlatform.ETERNALTD;
            default:
                return PluginPlatform.NONE;
        }
    }

    private void processImportsFolder() {
        for (File zippedFile : importsFolder.listFiles()) {
            if (zippedFile.getName().endsWith(".zip")) {
                unzipImportFile(zippedFile);
            } else if (pluginPlatform == PluginPlatform.FREEMINECRAFTMODELS && zippedFile.getName().endsWith(".bbmodel")) {
                processBbmodel(zippedFile);
            } else if (zippedFile.isDirectory()) {
                boolean incorrectlyUnzippedFolder = false;
                for (File iteratedFile : zippedFile.listFiles()) {
                    if (iteratedFile.getName().equalsIgnoreCase("pack.meta")) {
                        incorrectlyUnzippedFolder = true;
                        break;
                    }
                }
                if (incorrectlyUnzippedFolder) {
                    processUnzippedFile(zippedFile);
                } else {
//                    Logger.debug("Directory " + zippedFile.getAbsolutePath() + " does not contain pack.meta, skipping.");
                }
            } else {
                Logger.warn("File " + zippedFile.getPath() + " can't be imported! It will be skipped.");
            }
        }
    }

    private void unzipImportFile(File zippedFile) {
        try {
            File unzippedFolder = ZipFile.unzip(zippedFile, new File(zippedFile.getAbsolutePath().replace(".zip", "")));
            processUnzippedFile(unzippedFolder);
            deleteDirectory(zippedFile);
        } catch (Exception ex) {
            Logger.warn("Failed to unzip " + zippedFile.getPath() + " ! This probably means the file is corrupted.");
            Logger.warn("To fix this, delete this file from the imports folder and download a clean copy!");
            ex.printStackTrace();
        }
    }

    private void processUnzippedFile(File unzippedFolder) {
        PluginPlatform platform = pluginPlatform;
        //Check for pack.meta
        for (File unzippedFile : unzippedFolder.listFiles()) {
            if (unzippedFile.getName().equalsIgnoreCase("pack.meta")) {
                platform = getPluginPlatform(readPackMeta(unzippedFile));
            }
        }

        for (File unzippedFile : unzippedFolder.listFiles()) {
            moveUnzippedFiles(unzippedFile, platform);
        }
        deleteDirectory(unzippedFolder);
    }

    private void moveUnzippedFiles(File unzippedFile, PluginPlatform platform) {
        Path targetPath = getTargetPath(unzippedFile.getName(), platform);
        if (targetPath == null) {
            return;
        }
        // Create target directory and all parent directories if they don't exist
        // This ensures directories like plugins/EliteMobs/custombosses are created
        // even when EliteMobs isn't installed, so files are ready when it is
        if (!targetPath.toFile().exists()) {
            targetPath.toFile().mkdirs();
        }

        if (unzippedFile.isDirectory()) {
            if (unzippedFile.getName().equalsIgnoreCase("worldcontainer"))
                moveWorlds(unzippedFile);
            else
                moveDirectory(unzippedFile, targetPath);
        } else {
            moveFile(unzippedFile, targetPath);
        }
    }

    private Path getTargetPath(String folder, PluginPlatform platform) {
        if (platform == PluginPlatform.FREEMINECRAFTMODELS)
            return freeMinecraftModelsPath.resolve("models");
        switch (folder) {
            case "custombosses":
                if (platform == PluginPlatform.ELITEMOBS ||
                        //BetterStructures content sometimes has bosses, this reserves it
                        platform == PluginPlatform.BETTERSTRUCTURES ||
                        platform == PluginPlatform.EXTRACTIONCRAFT)
                    return eliteMobsPath.resolve("custombosses");
                break;
            case "customitems":
                if (platform == PluginPlatform.ELITEMOBS ||
                        //BetterStructures content sometimes has elite loot, this reserves it
                        platform == PluginPlatform.BETTERSTRUCTURES ||
                        platform == PluginPlatform.EXTRACTIONCRAFT)
                    return eliteMobsPath.resolve("customitems");
                break;
            case "customtreasurechests":
                if (platform == PluginPlatform.ELITEMOBS ||
                        //BetterStructures content sometimes has treasure chests (future?), this reserves it
                        platform == PluginPlatform.BETTERSTRUCTURES ||
                        platform == PluginPlatform.EXTRACTIONCRAFT)
                    return eliteMobsPath.resolve("customtreasurechests");
                break;
            case "dungeonpackages":
            case "content_packages":
                if (platform == PluginPlatform.ELITEMOBS)
                    return eliteMobsPath.resolve("content_packages");
                if (platform == PluginPlatform.BETTERSTRUCTURES)
                    return betterStructuresPath.resolve("content_packages");
                if (platform == PluginPlatform.EXTRACTIONCRAFT)
                    return extractioncraftPath.resolve("content_packages");
                break;
            case "customevents":
                if (platform == PluginPlatform.ELITEMOBS)
                    return eliteMobsPath.resolve("customevents");
                break;
            case "customspawns":
                if (platform == PluginPlatform.ELITEMOBS)
                    return eliteMobsPath.resolve("customspawns");
                break;
            case "customquests":
                if (platform == PluginPlatform.ELITEMOBS ||
                        platform == PluginPlatform.EXTRACTIONCRAFT)
                    return eliteMobsPath.resolve("customquests");
                break;
            case "customarenas":
                if (platform == PluginPlatform.ELITEMOBS)
                    return eliteMobsPath.resolve("customarenas");
                break;
            case "npcs":
                if (platform == PluginPlatform.ELITEMOBS ||
                        platform == PluginPlatform.EXTRACTIONCRAFT)
                    return eliteMobsPath.resolve("npcs");
                if (platform == PluginPlatform.ETERNALTD)
                    return eternalTDPath.resolve("npcs");
                break;
            case "wormholes":
                if (platform == PluginPlatform.ELITEMOBS)
                    return eliteMobsPath.resolve("wormholes");
                break;
            case "powers":
                if (platform == PluginPlatform.ELITEMOBS ||
                        platform == PluginPlatform.BETTERSTRUCTURES ||
                        platform == PluginPlatform.EXTRACTIONCRAFT)
                    return eliteMobsPath.resolve("powers");
                break;
            case "worldcontainer":
                try {
                    File wc = Bukkit.getWorldContainer().getCanonicalFile();
                    return wc.toPath().normalize().toAbsolutePath();
                } catch (IOException e) {
                    Logger.warn("Failed to resolve world container path canonically!");
                    e.printStackTrace();
                    return null;
                }
            case "world_blueprints":
                if (platform == PluginPlatform.ELITEMOBS)
                    return eliteMobsPath.resolve("world_blueprints");
                break;
            case "ModelEngine":
            case "models":
                modelsInstalled = true;
                if (Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels"))
                    return freeMinecraftModelsPath.resolve("models");
                else if (Bukkit.getPluginManager().isPluginEnabled("ModelEngine"))
                    return modelEnginePath.resolve("blueprints");
                else
                    return freeMinecraftModelsPath.resolve("models");
            case "schematics":
                if (platform == PluginPlatform.ELITEMOBS) {
                    Logger.warn("You just tried to import legacy content! Schematic dungeons no longer exist as of EliteMobs 9.0, use BetterStructures shrines instead!");
                    break;
                }
                if (platform == PluginPlatform.BETTERSTRUCTURES)
                    return betterStructuresPath.resolve("schematics");
                break;
            case "levels":
                if (platform == PluginPlatform.ETERNALTD)
                    return eternalTDPath.resolve("levels");
                break;
            case "waves":
                if (platform == PluginPlatform.ETERNALTD)
                    return eternalTDPath.resolve("waves");
                break;
            case "worlds":
                if (platform == PluginPlatform.ETERNALTD)
                    return eternalTDPath.resolve("worlds");
                break;
            case "elitemobs":
                if (platform == PluginPlatform.BETTERSTRUCTURES)
                    return eliteMobsPath;
                break;
            case "pack.meta":
                // Only for tagging purposes
                return null;
            case "spawn_pools":
                if (platform == PluginPlatform.BETTERSTRUCTURES ||
                        platform == PluginPlatform.EXTRACTIONCRAFT)
                    return betterStructuresPath.resolve("spawn_pools");
            case "components":
                if (platform == PluginPlatform.BETTERSTRUCTURES ||
                        platform == PluginPlatform.EXTRACTIONCRAFT)
                    return betterStructuresPath.resolve("components");
            case "modules":
                if (platform == PluginPlatform.BETTERSTRUCTURES ||
                        platform == PluginPlatform.EXTRACTIONCRAFT)
                    return betterStructuresPath.resolve("modules");
            case "module_generators":
                if (platform == PluginPlatform.BETTERSTRUCTURES ||
                        platform == PluginPlatform.EXTRACTIONCRAFT)
                    return betterStructuresPath.resolve("module_generators");
            case "loot_pools":
                if (platform == PluginPlatform.EXTRACTIONCRAFT)
                    return extractioncraftPath.resolve("loot_pools");
            case "loot_tables":
                if (platform == PluginPlatform.EXTRACTIONCRAFT)
                    return extractioncraftPath.resolve("loot_tables");
            case "resource_pack":
                if (platform == PluginPlatform.ELITEMOBS)
                    return eliteMobsPath.resolve("resource_pack");
            default:
                Logger.warn("Directory " + folder + " for zipped file was not recognized! Was the zipped file packaged correctly?");
                return null;
        }
        Logger.warn("Directory " + folder + " for zipped file was not recognized! Was the zipped file packaged correctly?");
        return null;
    }

    private String readPackMeta(File packMetaFile) {
        if (packMetaFile == null || !packMetaFile.exists()) {
            Logger.warn("File " + (packMetaFile != null ? packMetaFile.getPath() : "null") + " does not exist or is not valid.");
            return null;
        }

        try {
            Path filePath = packMetaFile.getCanonicalFile().toPath().normalize().toAbsolutePath();
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.warn("Failed to read pack.meta file " + packMetaFile.getPath() + ". Ensure the file is readable.");
            e.printStackTrace();
            return null;
        }
    }

    private void processBbmodel(File bbmodelFile) {
        Gson readerGson = new Gson();
        Reader reader;
        try {
            reader = Files.newBufferedReader(bbmodelFile.getCanonicalFile().toPath());
        } catch (Exception ex) {
            Logger.warn("Failed to read file " + bbmodelFile.getAbsolutePath());
            return;
        }

        Map<?, ?> jsonMap = readerGson.fromJson(reader, Map.class);

        // Detect version and merge groups with outliner
        int blockBenchVersion = detectVersion(jsonMap);
        List mergedOutliner = mergeGroupsAndOutliner(jsonMap, blockBenchVersion);

        Gson writerGson = new Gson();
        HashMap<String, Object> minifiedMap = new HashMap<>();

        minifiedMap.put("meta", jsonMap.get("meta"));
        minifiedMap.put("resolution", jsonMap.get("resolution"));
        minifiedMap.put("elements", jsonMap.get("elements"));
        minifiedMap.put("outliner", mergedOutliner);  // Use merged outliner instead of raw
        // Note: We don't put "groups" in minifiedMap since they're now merged into outliner

        ArrayList<Map> minifiedTextures = new ArrayList<>();
        ((ArrayList) jsonMap.get("textures")).forEach(innerMap -> minifiedTextures.add(Map.of(
                "source", ((LinkedTreeMap) innerMap).get("source"),
                "id", ((LinkedTreeMap) innerMap).get("id"),
                "name", ((LinkedTreeMap) innerMap).get("name"))
        ));
        minifiedMap.put("textures", minifiedTextures);
        minifiedMap.put("animations", jsonMap.get("animations"));

        List<String> parentFiles = new ArrayList<>();
        File currentFile = bbmodelFile;
        while (true) {
            File parentFile = currentFile.getParentFile();
            if (parentFile.getName().equals("imports")) break;
            parentFiles.add(parentFile.getName());
            currentFile = parentFile;
        }

        String modelsDirectory = freeMinecraftModelsPath.toFile().getAbsolutePath() + File.separatorChar + "models";
        String pathName;
        if (parentFiles.isEmpty()) {
            pathName = modelsDirectory + File.separatorChar + bbmodelFile.getName().replace(".bbmodel", ".fmmodel");
        } else {
            StringBuilder sb = new StringBuilder(modelsDirectory);
            for (int i = parentFiles.size() - 1; i >= 0; i--) {
                sb.append(File.separatorChar).append(parentFiles.get(i));
            }
            sb.append(File.separatorChar).append(bbmodelFile.getName().replace(".bbmodel", ".fmmodel"));
            pathName = sb.toString();
        }

        try {
            FileUtils.writeStringToFile(new File(pathName), writerGson.toJson(minifiedMap), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.warn("Failed to generate the minified file!");
            e.printStackTrace();
            throw new RuntimeException(e);
        }
    }

    /**
     * Detect the major version from the meta field
     */
    private int detectVersion(Map<?, ?> bbmodelData) {
        try {
            Map<?, ?> meta = (Map<?, ?>) bbmodelData.get("meta");
            if (meta == null) {
                Logger.warn("Missing 'meta' field in model. Defaulting to version 4.");
                return 4;
            }

            Object versionObj = meta.get("format_version");
            if (versionObj == null) {
                Logger.warn("Missing 'format_version' in meta. Defaulting to version 4.");
                return 4;
            }

            String versionStr = versionObj.toString();
            String[] parts = versionStr.split("\\.");
            return Integer.parseInt(parts[0]);

        } catch (Exception e) {
            Logger.warn("Failed to parse format_version. Error: " + e.getMessage() + ". Defaulting to version 4.");
            return 4;
        }
    }

    /**
     * For v4: just return the old all in one outliner
     * For v5: Merge groups array with outliner
     */
    private List mergeGroupsAndOutliner(Map<?, ?> bbmodelData, int blockBenchVersion) {
        List outlinerValues = (ArrayList) bbmodelData.get("outliner");

        if (blockBenchVersion < 5) {
            // v4 doesn't need merging
            return outlinerValues;
        }

        // v5: groups are separate
        List groupsList = (ArrayList) bbmodelData.get("groups");
        if (groupsList == null) {
            return outlinerValues;
        }

        // Create a map of group UUIDs to group objects for easy lookup
        HashMap<String, Map> groupsMap = new HashMap<>();
        for (Object groupObj : groupsList) {
            if (groupObj instanceof Map) {
                Map group = (Map) groupObj;
                String uuid = (String) group.get("uuid");
                if (uuid != null) {
                    groupsMap.put(uuid, group);
                }
            }
        }

        // Process outliner recursively and merge with group data
        return processOutlinerItems(outlinerValues, groupsMap);
    }

    /**
     * Recursively process outliner items and merge with group data from the groups array.
     * This traverses the entire tree structure, processing all children at every level.
     */
    private List processOutlinerItems(List items, HashMap<String, Map> groupsMap) {
        List result = new ArrayList();

        for (Object item : items) {
            if (item instanceof String) {
                // Direct UUID reference to an element (not a group)
                // These are leaf nodes that don't need merging
                result.add(item);
            } else if (item instanceof Map) {
                Map outlinerItem = (Map) item;
                String uuid = (String) outlinerItem.get("uuid");

                Map mergedItem;

                if (uuid != null && groupsMap.containsKey(uuid)) {
                    // Found matching group data - merge it in
                    Map groupData = groupsMap.get(uuid);
                    mergedItem = new HashMap(groupData);
                } else {
                    // No matching group, use outliner data as-is
                    mergedItem = new HashMap(outlinerItem);
                }

                // Recursively process children if they exist
                if (outlinerItem.containsKey("children")) {
                    List children = (List) outlinerItem.get("children");
                    if (children != null && !children.isEmpty()) {
                        List processedChildren = processOutlinerItems(children, groupsMap);
                        mergedItem.put("children", processedChildren);
                    }
                }

                result.add(mergedItem);
            }
        }

        return result;
    }

    private enum PluginPlatform {
        ELITEMOBS,
        EXTRACTIONCRAFT,
        BETTERSTRUCTURES,
        FREEMINECRAFTMODELS,
        ETERNALTD,
        RESURRECTIONCHEST,
        NONE
    }
}
