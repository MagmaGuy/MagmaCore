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
    private final Path eliteMobsPath = Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getParentFile().getAbsolutePath() + File.separatorChar + "EliteMobs");
    private final Path betterStructuresPath = Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getParentFile().getAbsolutePath() + File.separatorChar + "BetterStructures");
    private final Path freeMinecraftModelsPath = Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getParentFile().getAbsolutePath() + File.separatorChar + "FreeMinecraftModels");
    private final Path modelEnginePath = Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getParentFile().getAbsolutePath() + File.separatorChar + "ModelEngine");
    private final Path eternalTDPath = Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getParentFile().getAbsolutePath() + File.separatorChar + "EternalTD");
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
        if (file.isDirectory()) for (File iteratedFile : file.listFiles())
            if (iteratedFile != null) deleteDirectory(iteratedFile);
        Logger.info("Cleaning up " + file.getPath());
        file.delete();
    }

    private static void moveWorlds(File worldcontainerFile) {
        for (File file : worldcontainerFile.listFiles())
            try {
                File destinationFile = new File(Paths.get(Bukkit.getWorldContainer().getCanonicalPath() + File.separatorChar + file.getName()).normalize().toString());
                if (destinationFile.exists()) {
                    Logger.info("Overriding existing directory " + destinationFile.getPath());
                    if (Bukkit.getWorld(file.getName()) != null) {
                        Bukkit.unloadWorld(file.getName(), false);
                        Logger.warn("Unloaded world " + file.getName() + " for safe replacement!");
                    }
                    deleteDirectory(destinationFile);
                }
                moveDirectory(file, destinationFile.toPath());
//                FileUtils.moveDirectory(file, destinationFile);
            } catch (Exception exception) {
                Logger.warn("Failed to move worlds for " + file.getName() + "! Tell the dev!");
                exception.printStackTrace();
            }
    }

    private static void moveDirectory(File unzippedDirectory, Path targetPath) {
        for (File file : unzippedDirectory.listFiles())
            try {
                Logger.info("Adding " + file.getCanonicalPath());
                moveFile(file, targetPath);
            } catch (Exception exception) {
                Logger.warn("Failed to move directories for " + file.getName() + "! Tell the dev!");
                exception.printStackTrace();
            }
    }

    private static void moveFile(File file, Path targetPath) {
        try {
            if (file.isDirectory()) {
                if (Paths.get(targetPath + "" + File.separatorChar + file.getName()).toFile().exists())
                    for (File iteratedFile : file.listFiles())
                        moveFile(iteratedFile, Paths.get(targetPath + "" + File.separatorChar + file.getName()));
                else {
                    targetPath.toFile().mkdirs();
                    Files.move(file.toPath(), Paths.get(targetPath + "" + File.separatorChar + file.getName()), StandardCopyOption.REPLACE_EXISTING);
                }
            } else if (targetPath.toFile().exists()) {
                targetPath.toFile().mkdirs();
                Files.move(file.toPath(), Paths.get(targetPath + "" + File.separatorChar + file.getName()), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Exception exception) {
            Logger.warn("Failed to move directories for " + file.getName() + "! Tell the dev!");
            exception.printStackTrace();
        }
    }

    private boolean createImportsDirectory() {
        Path configurationsPath = Paths.get(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getAbsolutePath());
        if (!Files.isDirectory(Paths.get(configurationsPath.normalize() + "" + File.separatorChar + "imports"))) {
            try {
                Files.createDirectory(Paths.get(configurationsPath.normalize() + "" + File.separatorChar + "imports"));
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
            return new File(Paths.get(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getCanonicalPath() + File.separatorChar + "imports").toString());
        } catch (Exception ex) {
            Logger.warn("Failed to get imports folder! Report this to the dev!");
            return null;
        }
    }

    private PluginPlatform getPluginPlatform(String name) {
        if (name == null) return PluginPlatform.NONE;
        switch (name.toLowerCase(Locale.ROOT)) {
            case "elitemobs":
                return PluginPlatform.ELITEMOBS;
            case "betterstructures":
                return PluginPlatform.BETTERSTRUCTURES;
            case "freeminecraftmodels":
                return PluginPlatform.FREEMINECRAFTMODELS;
            case "eternald":
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
        if (targetPath == null) return;
        Logger.debug("target path " + targetPath.toAbsolutePath());
        if (targetPath.toFile().getParentFile() != null && !targetPath.toFile().getParentFile().exists())
            targetPath.toFile().mkdirs();
        if (targetPath.toFile().exists()) targetPath.toFile().mkdir();
        if (unzippedFile.isDirectory())
            if (unzippedFile.getName().equalsIgnoreCase("worldcontainer"))
                moveWorlds(unzippedFile);
            else
                moveDirectory(unzippedFile, targetPath);
        else
            moveFile(unzippedFile, targetPath);
    }

    private Path getTargetPath(String folder, PluginPlatform platform) {
        if (platform == PluginPlatform.FREEMINECRAFTMODELS)
            return Path.of(freeMinecraftModelsPath.toFile().getAbsolutePath() + File.separatorChar + "models");
        switch (folder) {
            case "custombosses":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "custombosses");
            case "customitems":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "customitems");
            case "customtreasurechests":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "customtreasurechests");
            case "dungeonpackages":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "dungeonpackages");
            case "customevents":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "customevents");
            case "customspawns":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "customspawns");
            case "customquests":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "customquests");
            case "customarenas":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "customarenas");
            case "npcs":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "npcs");
                if (platform == PluginPlatform.ETERNALTD)
                    return Path.of(eternalTDPath.toFile().getAbsolutePath() + File.separatorChar + "npcs");
            case "wormholes":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "wormholes");
            case "powers":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "powers");
            case "worldcontainer":
                return Bukkit.getWorldContainer().toPath();
            case "world_blueprints":
                if (platform == PluginPlatform.ELITEMOBS)
                    return Path.of(eliteMobsPath.toFile().getAbsolutePath() + File.separatorChar + "world_blueprints");
            case "ModelEngine", "models":
                modelsInstalled = true;
                if (Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels"))
                    return Path.of(freeMinecraftModelsPath.toFile().getAbsolutePath() + File.separatorChar + "models");
                else if (Bukkit.getPluginManager().isPluginEnabled("ModelEngine"))
                    return Path.of(modelEnginePath.toFile().getAbsolutePath() + File.separatorChar + "blueprints");
                else
                    return Path.of(freeMinecraftModelsPath.toFile().getAbsolutePath() + File.separatorChar + "models");
            case "schematics":
                if (platform == PluginPlatform.ELITEMOBS) {
                    Logger.warn("You just tried to import legacy content! Schematic dungeons no longer exist as of EliteMobs 9.0, use BetterStructures shrines instead!");
                    break;
                }
                if (platform == PluginPlatform.BETTERSTRUCTURES)
                    return Path.of(betterStructuresPath.toFile().getAbsolutePath() + File.separatorChar + "schematics");
            case "levels":
                if (platform == PluginPlatform.ETERNALTD)
                    return Path.of(eternalTDPath.toFile().getAbsolutePath() + File.separatorChar + "levels");
            case "waves":
                if (platform == PluginPlatform.ETERNALTD)
                    return Path.of(eternalTDPath.toFile().getAbsolutePath() + File.separatorChar + "waves");
            case "worlds":
                if (platform == PluginPlatform.ETERNALTD)
                    return Path.of(eternalTDPath.toFile().getAbsolutePath() + File.separatorChar + "worlds");
            case "elitemobs":
                if (platform == PluginPlatform.BETTERSTRUCTURES)
                    return eliteMobsPath;
            case "pack.meta":
                //Only for tagging purposes
                return null;
            default:
                Logger.warn("Directory " + folder + " for zipped file was not a recognized directory for the file import system! Was the zipped file packaged correctly?");
                return null;
        }
        Logger.warn("Directory " + folder + " for zipped file was not a recognized directory for the file import system! Was the zipped file packaged correctly?");
        return null;
    }

    private String readPackMeta(File packMetaFile) {
        if (packMetaFile == null || !packMetaFile.exists()) {
            Logger.warn("File " + (packMetaFile != null ? packMetaFile.getPath() : "null") + " does not exist or is not valid.");
            return null;
        }

        try {
            Path filePath = packMetaFile.toPath();
            return Files.readString(filePath, StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.warn("Failed to read pack.meta file " + packMetaFile.getPath() + ". Ensure the file is readable.");
            e.printStackTrace();
            return null;
        }
    }

    private void processBbmodel(File bbmodelFile) {
        //Reading time
        Gson readerGson = new Gson();

        Reader reader;
        // create a reader
        try {
            reader = Files.newBufferedReader(Paths.get(bbmodelFile.getPath()));
        } catch (Exception ex) {
            Logger.warn("Failed to read file " + bbmodelFile.getAbsolutePath());
            return;
        }

        // convert JSON file to map
        Map<?, ?> jsonMap = readerGson.fromJson(reader, Map.class);

        //Writing time
        Gson writerGson = new Gson();
        //The objective here is to get every map that is actually used, and avoid every map that is not.
        HashMap<String, Object> minifiedMap = new HashMap<>();

        minifiedMap.put("resolution", jsonMap.get("resolution"));
        minifiedMap.put("elements", jsonMap.get("elements"));
        minifiedMap.put("outliner", jsonMap.get("outliner"));
        ArrayList<Map> minifiedTextures = new ArrayList<>();
        ((ArrayList) jsonMap.get("textures")).forEach(innerMap -> minifiedTextures.add(Map.of("source", ((LinkedTreeMap) innerMap).get("source"), "id", ((LinkedTreeMap) innerMap).get("id"), "name", ((LinkedTreeMap) innerMap).get("name"))));
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
        if (parentFiles.isEmpty())
            pathName = modelsDirectory + File.separatorChar + bbmodelFile.getName().replace(".bbmodel", ".fmmodel");
        else {
            pathName = modelsDirectory;
            for (int i = parentFiles.size() - 1; i > -1; i--)
                pathName += File.separatorChar + parentFiles.get(i);
            pathName += File.separatorChar + bbmodelFile.getName().replace(".bbmodel", ".fmmodel");
        }

        try {
            FileUtils.writeStringToFile(new File(pathName), writerGson.toJson(minifiedMap), StandardCharsets.UTF_8);
        } catch (IOException e) {
            Logger.warn("Failed to generate the minified file!");
            throw new RuntimeException(e);
        }
    }

    private enum PluginPlatform {
        ELITEMOBS,
        BETTERSTRUCTURES,
        FREEMINECRAFTMODELS,
        ETERNALTD,
        NONE
    }
}
