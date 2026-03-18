package com.magmaguy.magmacore.dlc;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.events.ModelInstallationEvent;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.ZipFile;
import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.ExecutionException;

public class ConfigurationImporter {
    private final JavaPlugin ownerPlugin;
    private final Path eliteMobsPath;
    private final Path extractioncraftPath;
    private final Path betterStructuresPath;
    private final Path resurrectionChestPath;
    private final Path freeMinecraftModelsPath;
    private final Path modelEnginePath;
    private final Path eternalTDPath;
    private final Path megaBlockSurvivorsPath;
    private final Path worldCannonPath;
    private PluginPlatform pluginPlatform;
    private File importsFolder;
    private boolean modelsInstalled = false;

    public ConfigurationImporter(JavaPlugin ownerPlugin) {
        this.ownerPlugin = ownerPlugin == null ? MagmaCore.getInstance().getRequestingPlugin() : ownerPlugin;
        Path pluginsDirectory = Path.of(this.ownerPlugin.getDataFolder().getParentFile().getAbsolutePath());
        eliteMobsPath = pluginsDirectory.resolve("EliteMobs");
        extractioncraftPath = pluginsDirectory.resolve("Extractioncraft");
        betterStructuresPath = pluginsDirectory.resolve("BetterStructures");
        resurrectionChestPath = pluginsDirectory.resolve("ResurrectionChest");
        freeMinecraftModelsPath = pluginsDirectory.resolve("FreeMinecraftModels");
        modelEnginePath = pluginsDirectory.resolve("ModelEngine");
        eternalTDPath = pluginsDirectory.resolve("EternalTD");
        megaBlockSurvivorsPath = pluginsDirectory.resolve("MegaBlockSurvivors");
        worldCannonPath = pluginsDirectory.resolve("CannonRTP");
        if (!createImportsDirectory()) return;
        importsFolder = getImportsDirectory();
        if (importsFolder == null || importsFolder.listFiles().length == 0) return;
        pluginPlatform = getPluginPlatform(this.ownerPlugin.getName());
        processImportsFolder();
        if (Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels") && modelsInstalled
                && !this.ownerPlugin.getName().equals("FreeMinecraftModels")) {
            if (Bukkit.isPrimaryThread()) {
                Bukkit.getPluginManager().callEvent(new ModelInstallationEvent());
            } else {
                Bukkit.getScheduler().runTask(this.ownerPlugin,
                        () -> Bukkit.getPluginManager().callEvent(new ModelInstallationEvent()));
            }
        }
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

    private void moveWorlds(File worldcontainerFile) {
        for (File file : worldcontainerFile.listFiles()) {
            try {
                File worldContainer = Bukkit.getWorldContainer().getCanonicalFile();
                Path worldContainerPath = worldContainer.toPath().normalize().toAbsolutePath();
                Path destinationPath = worldContainerPath.resolve(file.getName());
                File destinationFile = destinationPath.toFile();

                if (destinationFile.exists()) {
                    Logger.info("Overriding existing directory " + destinationFile.getPath());
                    if (Bukkit.getWorld(file.getName()) != null) {
                        if (Bukkit.isPrimaryThread()) {
                            Bukkit.unloadWorld(file.getName(), false);
                        } else {
                            try {
                                Bukkit.getScheduler().callSyncMethod(
                                        ownerPlugin,
                                        () -> Bukkit.unloadWorld(file.getName(), false)
                                ).get();
                            } catch (InterruptedException | ExecutionException e) {
                                Logger.warn("Failed to unload world " + file.getName() + " on main thread!");
                                e.printStackTrace();
                            }
                        }
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
        Path configurationsPath = Paths.get(ownerPlugin.getDataFolder().getAbsolutePath());
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
            File dir = Paths.get(ownerPlugin.getDataFolder().getCanonicalPath()).resolve("imports").toFile();
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
            case "resurrectionchest":
                return PluginPlatform.RESURRECTIONCHEST;
            case "freeminecraftmodels":
                return PluginPlatform.FREEMINECRAFTMODELS;
            case "eternaltd":
                return PluginPlatform.ETERNALTD;
            case "megablocksurvivors":
                return PluginPlatform.MEGABLOCKSURVIVORS;
            case "cannonrtp":
            case "worldcannon":
            case "world_cannon":
                return PluginPlatform.WORLDCANNON;
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
        return ConfigurationImportProfiles.resolve(this, folder, platform);
    }

    Path getEliteMobsPath() {
        return eliteMobsPath;
    }

    Path getExtractioncraftPath() {
        return extractioncraftPath;
    }

    Path getBetterStructuresPath() {
        return betterStructuresPath;
    }

    Path getResurrectionChestPath() {
        return resurrectionChestPath;
    }

    Path getFreeMinecraftModelsPath() {
        return freeMinecraftModelsPath;
    }

    Path getModelEnginePath() {
        return modelEnginePath;
    }

    Path getEternalTDPath() {
        return eternalTDPath;
    }

    Path getMegaBlockSurvivorsPath() {
        return megaBlockSurvivorsPath;
    }

    Path getWorldCannonPath() {
        return worldCannonPath;
    }

    void markModelsInstalled() {
        modelsInstalled = true;
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

    enum PluginPlatform {
        ELITEMOBS,
        EXTRACTIONCRAFT,
        BETTERSTRUCTURES,
        FREEMINECRAFTMODELS,
        ETERNALTD,
        MEGABLOCKSURVIVORS,
        WORLDCANNON,
        RESURRECTIONCHEST,
        NONE
    }
}
