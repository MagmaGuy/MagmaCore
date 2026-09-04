package com.magmaguy.magmacore.dlc;

import com.google.gson.Gson;
import com.google.gson.internal.LinkedTreeMap;
import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.events.ModelInstallationEvent;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.WorldFolderResolver;
import com.magmaguy.magmacore.util.ZipFile;
import org.apache.commons.io.FileUtils;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AccessDeniedException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.nio.file.StandardCopyOption;
import java.util.*;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import java.util.stream.Stream;

public class ConfigurationImporter {
    private static final int WINDOWS_ACCESS_RETRY_ATTEMPTS = 5;
    private static final long WINDOWS_ACCESS_RETRY_BASE_DELAY_MILLIS = 25L;
    private static final long SYNC_WORLD_UNLOAD_POLL_MILLIS = 25L;
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
    private boolean eliteMobsContentImported = false;

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
        if (importsFolder == null) return;
        try {
            if (sortedChildren(importsFolder).length == 0) return;
        } catch (IOException exception) {
            Logger.warn("Failed to inspect import directory " + importsFolder.getPath() + "!");
            exception.printStackTrace();
            return;
        }
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
            File[] children = file.listFiles();
            if (children != null) {
                for (File iteratedFile : children) {
                    if (iteratedFile != null) deleteDirectory(iteratedFile);
                }
            }
        }
        Logger.info("Cleaning up " + file.getPath());
        file.delete();
    }

    private void moveWorlds(
            File worldcontainerFile,
            ImportTransaction transaction) throws IOException {
        for (File file : sortedChildren(worldcontainerFile)) {
            File worldContainer = Bukkit.getWorldContainer().getCanonicalFile();
            Path worldContainerPath = worldContainer.toPath().normalize().toAbsolutePath();
            Path destinationPath = worldContainerPath.resolve(file.getName());
            File destinationFile = destinationPath.toFile();

            if (destinationFile.exists() || WorldFolderResolver.hasModernLayout(file.getName())) {
                Logger.info("Overriding existing directory " + destinationFile.getPath());
                if (Bukkit.getWorld(file.getName()) != null) {
                    boolean unloaded;
                    if (Bukkit.isPrimaryThread()) {
                        unloaded = Bukkit.unloadWorld(file.getName(), false);
                    } else {
                        Future<Boolean> unloadFuture =
                                Bukkit.getScheduler().callSyncMethod(
                                        ownerPlugin,
                                        () -> Bukkit.unloadWorld(
                                                file.getName(),
                                                false));
                        unloaded = awaitWorldUnload(
                                unloadFuture,
                                () -> MagmaCore.isShutdownRequested(ownerPlugin),
                                file.getName());
                    }
                    if (!unloaded) {
                        throw new IOException(
                                "World " + file.getName()
                                        + " refused to unload; retaining the import archive "
                                        + "rather than replacing a live world directory.");
                    }
                    Logger.warn("Unloaded world " + file.getName() + " for safe replacement!");
                }
                // Move both layouts into the package transaction instead of deleting
                // them. If any later file in this archive fails, rollback restores the
                // complete prior world rather than leaving a half-imported replacement.
                transaction.displaceDirectory(
                        WorldFolderResolver.legacyFolder(file.getName()));
                transaction.displaceDirectory(
                        WorldFolderResolver.modernFolder(file.getName()));
            }
            moveDirectory(file, destinationPath, transaction);
        }
    }

    /**
     * Waits for a Bukkit main-thread world unload without creating a shutdown deadlock.
     *
     * <p>Plugin shutdown runs on the main thread and waits for async initialization to become
     * quiescent. If the unload task is still queued at that point, an unbounded Future#get() would
     * make each side wait for the other forever. Polling keeps normal imports synchronous while
     * allowing shutdown to cancel a task that has not started and unwind the import transaction.
     */
    static boolean awaitWorldUnload(
            Future<Boolean> unloadFuture,
            BooleanSupplier shutdownRequested,
            String worldName) throws IOException {
        Objects.requireNonNull(unloadFuture, "unloadFuture");
        Objects.requireNonNull(shutdownRequested, "shutdownRequested");
        while (true) {
            if (shutdownRequested.getAsBoolean()) {
                throw cancelWorldUnloadForShutdown(unloadFuture, worldName, null);
            }
            try {
                boolean unloaded = unloadFuture.get(
                        SYNC_WORLD_UNLOAD_POLL_MILLIS,
                        TimeUnit.MILLISECONDS);
                if (shutdownRequested.getAsBoolean()) {
                    throw cancelWorldUnloadForShutdown(
                            unloadFuture,
                            worldName,
                            null);
                }
                return unloaded;
            } catch (TimeoutException ignored) {
                // Recheck the initialization lifecycle before waiting again.
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw cancelWorldUnloadForShutdown(
                        unloadFuture,
                        worldName,
                        exception);
            } catch (CancellationException exception) {
                InterruptedIOException interrupted =
                        new InterruptedIOException(
                                "Main-thread world unload was canceled for "
                                        + worldName + ".");
                interrupted.initCause(exception);
                throw interrupted;
            } catch (ExecutionException exception) {
                Throwable cause = exception.getCause() == null
                        ? exception
                        : exception.getCause();
                throw new IOException(
                        "Failed to unload world " + worldName
                                + " on the main thread.",
                        cause);
            }
        }
    }

    private static InterruptedIOException cancelWorldUnloadForShutdown(
            Future<Boolean> unloadFuture,
            String worldName,
            Throwable cause) {
        unloadFuture.cancel(false);
        InterruptedIOException interrupted =
                new InterruptedIOException(
                        "Shutdown requested while waiting to unload world "
                                + worldName + ".");
        if (cause != null) interrupted.initCause(cause);
        return interrupted;
    }

    private static void moveDirectory(
            File unzippedDirectory,
            Path targetPath,
            ImportTransaction transaction) throws IOException {
        transaction.prepareDirectory(targetPath);
        for (File file : sortedChildren(unzippedDirectory)) {
            mergeImportEntry(
                    file.toPath(),
                    targetPath.resolve(file.getName()),
                    transaction);
        }
    }

    private static void moveFile(
            File file,
            Path targetPath,
            ImportTransaction transaction) throws IOException {
        transaction.prepareDirectory(targetPath);
        mergeImportEntry(
                file.toPath(),
                targetPath.resolve(file.getName()),
                transaction);
    }

    /**
     * Merges one extracted import entry into its final destination without renaming the
     * source directory. Renaming a populated directory is fragile on Windows: any transient
     * handle opened by a scanner is enough for {@link Files#move(Path, Path, java.nio.file.CopyOption...)}
     * to fail with {@link AccessDeniedException}. Copying each file through a sibling temporary
     * file also keeps the complete extracted source available until the caller has successfully
     * installed every entry and can safely remove the archive.
     */
    static void mergeImportEntry(Path source, Path destination) throws IOException {
        Path normalizedDestination = destination.normalize().toAbsolutePath();
        Path transactionParent = normalizedDestination.getParent();
        if (transactionParent == null) {
            throw new IOException(
                    "Import destination has no parent: " + destination);
        }
        Files.createDirectories(transactionParent);
        ImportTransaction transaction =
                ImportTransaction.create(transactionParent);
        try {
            mergeImportEntry(source, destination, transaction);
            transaction.commit();
        } catch (IOException | RuntimeException failure) {
            transaction.rollback(failure);
            throw failure;
        }
    }

    private static void mergeImportEntry(
            Path source,
            Path destination,
            ImportTransaction transaction) throws IOException {
        Path normalizedSource = source.normalize().toAbsolutePath();
        Path normalizedDestination = destination.normalize().toAbsolutePath();
        if (Files.isDirectory(normalizedSource)) {
            transaction.prepareDirectory(normalizedDestination);
            try (Stream<Path> children = Files.list(normalizedSource)) {
                List<Path> sortedChildren = children
                        .sorted(Comparator.comparing(path -> path.getFileName().toString(),
                                String.CASE_INSENSITIVE_ORDER))
                        .toList();
                for (Path child : sortedChildren) {
                    mergeImportEntry(
                            child,
                            normalizedDestination.resolve(child.getFileName()),
                            transaction);
                }
            }
            return;
        }

        if (!Files.isRegularFile(normalizedSource)) {
            throw new IOException(
                    "Import source is not a regular file: " + normalizedSource);
        }
        transaction.prepareFile(normalizedDestination);
        copyFileAtomicallyWithRetry(normalizedSource, normalizedDestination);
    }

    private static void copyFileAtomicallyWithRetry(Path source, Path destination) throws IOException {
        for (int attempt = 1; attempt <= WINDOWS_ACCESS_RETRY_ATTEMPTS; attempt++) {
            try {
                copyFileAtomically(source, destination);
                return;
            } catch (AccessDeniedException exception) {
                if (attempt == WINDOWS_ACCESS_RETRY_ATTEMPTS) throw exception;
                try {
                    Thread.sleep(WINDOWS_ACCESS_RETRY_BASE_DELAY_MILLIS << (attempt - 1));
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    InterruptedIOException interrupted =
                            new InterruptedIOException("Interrupted while retrying import of " + source);
                    interrupted.initCause(interruptedException);
                    throw interrupted;
                }
            }
        }
    }

    private static void copyFileAtomically(Path source, Path destination) throws IOException {
        String destinationName = destination.getFileName().toString();
        Path temporary = Files.createTempFile(destination.getParent(),
                "." + destinationName + ".import-", ".tmp");
        try {
            Files.copy(source, temporary, StandardCopyOption.REPLACE_EXISTING);
            try {
                Files.move(temporary, destination,
                        StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static File[] sortedChildren(File directory) throws IOException {
        File[] children = directory.listFiles();
        if (children == null) throw new IOException("Failed to list directory " + directory.getPath());
        Arrays.sort(children, Comparator.comparing(File::getName, String.CASE_INSENSITIVE_ORDER));
        return children;
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

    static PluginPlatform getPluginPlatform(String name) {
        if (name == null) return PluginPlatform.NONE;
        // pack.meta is plain text written by the DLC pipeline and sometimes by
        // hand, so it routinely carries a trailing newline, CRLF, or a BOM from
        // a Windows editor. None of that changes which plugin the package
        // declares. Matching the raw content meant a package whose pack.meta
        // simply ended with a newline resolved to NONE and was rejected as
        // "does not declare a supported plugin platform", then retained and
        // retried forever without ever importing.
        String declaredPlatform = name
                .replace("﻿", "")
                .trim();
        switch (declaredPlatform.toLowerCase(Locale.ROOT)) {
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
        File[] importEntries;
        try {
            importEntries = sortedChildren(importsFolder);
        } catch (IOException exception) {
            Logger.warn("Failed to inspect import directory " + importsFolder.getPath() + "!");
            exception.printStackTrace();
            return;
        }
        for (File zippedFile : importEntries) {
            // OS metadata files (.DS_Store, ._* AppleDouble, Thumbs.db, desktop.ini) are not importable content
            if (!zippedFile.isDirectory() && isOsMetadataFile(zippedFile.getName())) continue;
            if (zippedFile.getName().endsWith(".zip")) {
                unzipImportFile(zippedFile);
            } else if (pluginPlatform == PluginPlatform.FREEMINECRAFTMODELS && zippedFile.getName().endsWith(".bbmodel")) {
                processBbmodel(zippedFile);
            } else if (zippedFile.isDirectory()) {
                boolean incorrectlyUnzippedFolder = false;
                File[] children;
                try {
                    children = sortedChildren(zippedFile);
                } catch (IOException exception) {
                    Logger.warn("Failed to inspect import directory " + zippedFile.getPath() + "!");
                    exception.printStackTrace();
                    continue;
                }
                for (File iteratedFile : children) {
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

    private static boolean isOsMetadataFile(String fileName) {
        return fileName.startsWith(".")
                || fileName.equalsIgnoreCase("Thumbs.db")
                || fileName.equalsIgnoreCase("desktop.ini");
    }

    private void unzipImportFile(File zippedFile) {
        Path stagingDirectory = null;
        try {
            String archiveName = zippedFile.getName();
            String baseName = archiveName.substring(
                    0, archiveName.length() - ".zip".length())
                    .replaceAll("[^A-Za-z0-9._-]", "_");
            stagingDirectory = Files.createTempDirectory(
                    importsFolder.toPath(),
                    "." + baseName + ".extract-");
            File unzippedFolder = ZipFile.unzip(
                    zippedFile,
                    stagingDirectory.toFile());
            if (processUnzippedFile(unzippedFolder)) {
                deleteDirectory(zippedFile);
            } else {
                Logger.warn("Import failed for " + zippedFile.getPath()
                        + "; retaining the archive for a safe retry.");
            }
        } catch (Exception ex) {
            Logger.warn("Failed to unzip " + zippedFile.getPath() + " ! This probably means the file is corrupted.");
            Logger.warn("To fix this, delete this file from the imports folder and download a clean copy!");
            ex.printStackTrace();
        } finally {
            if (stagingDirectory != null &&
                    Files.exists(stagingDirectory)) {
                deleteDirectory(stagingDirectory.toFile());
            }
        }
    }

    private boolean processUnzippedFile(File unzippedFolder) {
        PluginPlatform platform = pluginPlatform;
        File[] unzippedFiles;
        try {
            unzippedFiles = sortedChildren(unzippedFolder);
        } catch (IOException exception) {
            Logger.warn("Failed to inspect extracted import " + unzippedFolder.getPath() + "!");
            exception.printStackTrace();
            return false;
        }
        //Check for pack.meta
        for (File unzippedFile : unzippedFiles) {
            if (unzippedFile.getName().equalsIgnoreCase("pack.meta")) {
                platform = getPluginPlatform(readPackMeta(unzippedFile));
            }
        }

        if (platform == PluginPlatform.NONE) {
            Logger.warn("Import " + unzippedFolder.getPath()
                    + " does not declare a supported plugin platform; "
                    + "the archive will be retained.");
            return false;
        }

        List<ImportCandidate> candidates = new ArrayList<>();
        boolean rejected = false;
        for (File unzippedFile : unzippedFiles) {
            if (ConfigurationImportRegistry.isSkippedFolder(
                    unzippedFile.getName())) {
                continue;
            }
            Path targetPath =
                    getTargetPath(unzippedFile.getName(), platform);
            if (targetPath == null) {
                if (!ConfigurationImportRegistry.hasResolver(
                        unzippedFile.getName(), platform)) {
                    rejected = true;
                    Logger.warn("Rejecting import because top-level entry '"
                            + unzippedFile.getName()
                            + "' is not recognized for " + platform + ".");
                }
                continue;
            }
            candidates.add(new ImportCandidate(unzippedFile, targetPath));
        }
        if (rejected || candidates.isEmpty()) {
            if (candidates.isEmpty()) {
                Logger.warn("Import " + unzippedFolder.getPath()
                        + " contains no installable content.");
            }
            return false;
        }

        ImportTransaction transaction;
        try {
            transaction = ImportTransaction.create(importsFolder.toPath());
        } catch (IOException exception) {
            Logger.warn("Failed to create a transaction for import "
                    + unzippedFolder.getPath() + "; the archive will be retained.");
            exception.printStackTrace();
            return false;
        }
        try {
            for (ImportCandidate candidate : candidates) {
                moveUnzippedFiles(
                        candidate.source(),
                        candidate.targetPath(),
                        transaction);
            }
            transaction.commit();
            for (ImportCandidate candidate : candidates) {
                recordSuccessfulImport(candidate);
            }
        } catch (IOException | RuntimeException exception) {
            Logger.warn("Failed to import " + unzippedFolder.getPath()
                    + "; rolling back every destination changed by this archive "
                    + "and retaining it for retry.");
            try {
                transaction.rollback(exception);
            } catch (IOException rollbackFailure) {
                Logger.warn("Import rollback was incomplete for "
                        + unzippedFolder.getPath()
                        + "; inspect the logged destinations before retrying.");
                rollbackFailure.printStackTrace();
            }
            exception.printStackTrace();
            return false;
        }
        deleteDirectory(unzippedFolder);
        return true;
    }

    private void moveUnzippedFiles(
            File unzippedFile,
            Path targetPath,
            ImportTransaction transaction) throws IOException {
        // Create target directory and all parent directories if they don't exist
        // This ensures directories like plugins/EliteMobs/custombosses are created
        // even when EliteMobs isn't installed, so files are ready when it is
        transaction.prepareDirectory(targetPath);

        if (unzippedFile.isDirectory()) {
            if (unzippedFile.getName().equalsIgnoreCase("worldcontainer"))
                moveWorlds(unzippedFile, transaction);
            else
                moveDirectory(unzippedFile, targetPath, transaction);
        } else {
            moveFile(unzippedFile, targetPath, transaction);
        }
    }

    private void recordSuccessfulImport(ImportCandidate candidate) {
        String sourceName = candidate.source().getName();
        if (sourceName.equalsIgnoreCase("models") ||
                sourceName.equalsIgnoreCase("modelengine")) {
            modelsInstalled = true;
        }
        // EliteMobs only reads its config folders on boot/reload, so when another plugin's
        // pack drops files into plugins/EliteMobs (e.g. BetterStructures elite shrines),
        // the owner plugin must know to trigger an EliteMobs reload afterwards.
        if (!ownerPlugin.getName().equalsIgnoreCase("EliteMobs")
                && candidate.targetPath().normalize().toAbsolutePath()
                .startsWith(eliteMobsPath.normalize().toAbsolutePath())) {
            eliteMobsContentImported = true;
        }
    }

    private Path getTargetPath(String folder, PluginPlatform platform) {
        return ConfigurationImportProfiles.resolve(this, folder, platform);
    }

    static void mergeImportEntriesAtomically(
            List<ImportTransfer> transfers,
            Path transactionWorkspace) throws IOException {
        ImportTransaction transaction =
                ImportTransaction.create(transactionWorkspace);
        try {
            for (ImportTransfer transfer : transfers) {
                mergeImportEntry(
                        transfer.source(),
                        transfer.destination(),
                        transaction);
            }
            transaction.commit();
        } catch (IOException | RuntimeException failure) {
            transaction.rollback(failure);
            throw failure;
        }
    }

    record ImportTransfer(Path source, Path destination) {
        ImportTransfer {
            Objects.requireNonNull(source, "source");
            Objects.requireNonNull(destination, "destination");
        }
    }

    /**
     * Per-archive mutation journal. Existing files are copied into a private
     * backup area before replacement, newly-created paths are recorded, and
     * whole world directories are displaced by rename. A later failure can
     * therefore restore the exact pre-import state across every top-level
     * candidate instead of retaining a retryable archive beside partially
     * installed content.
     */
    private static final class ImportTransaction {
        private final Path backupRoot;
        private final Map<Path, Path> fileBackups = new LinkedHashMap<>();
        private final Map<Path, Path> displacedDirectories =
                new LinkedHashMap<>();
        private final Set<Path> createdFiles = new LinkedHashSet<>();
        private final Set<Path> createdDirectories = new LinkedHashSet<>();
        private int nextBackupId;
        private boolean closed;

        private ImportTransaction(Path backupRoot) {
            this.backupRoot = backupRoot;
        }

        static ImportTransaction create(Path workspace) throws IOException {
            Path normalizedWorkspace =
                    workspace.normalize().toAbsolutePath();
            Files.createDirectories(normalizedWorkspace);
            return new ImportTransaction(Files.createTempDirectory(
                    normalizedWorkspace,
                    ".import-transaction-"));
        }

        void prepareDirectory(Path directory) throws IOException {
            ensureOpen();
            Path normalized = directory.normalize().toAbsolutePath();
            if (Files.exists(normalized,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(normalized) ||
                        !Files.isDirectory(normalized,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(
                            "Import directory target is not a safe directory: "
                                    + normalized);
                }
                return;
            }

            List<Path> missing = new ArrayList<>();
            Path cursor = normalized;
            while (cursor != null &&
                    !Files.exists(cursor,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                missing.add(cursor);
                cursor = cursor.getParent();
            }
            if (cursor != null && (Files.isSymbolicLink(cursor) ||
                    !Files.isDirectory(cursor,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException(
                        "Import directory parent is not a safe directory: "
                                + cursor);
            }
            Files.createDirectories(normalized);
            createdDirectories.addAll(missing);
        }

        void prepareFile(Path destination) throws IOException {
            ensureOpen();
            Path normalized = destination.normalize().toAbsolutePath();
            Path parent = normalized.getParent();
            if (parent == null) {
                throw new IOException(
                        "Import file target has no parent: " + normalized);
            }
            prepareDirectory(parent);
            if (fileBackups.containsKey(normalized) ||
                    createdFiles.contains(normalized)) {
                return;
            }
            if (Files.exists(normalized,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(normalized) ||
                        !Files.isRegularFile(normalized,
                                java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                    throw new IOException(
                            "Import file target is not a safe regular file: "
                                    + normalized);
                }
                Path backup = nextBackupPath("files");
                Files.copy(
                        normalized,
                        backup,
                        StandardCopyOption.COPY_ATTRIBUTES);
                fileBackups.put(normalized, backup);
            } else {
                createdFiles.add(normalized);
            }
        }

        void displaceDirectory(Path directory) throws IOException {
            ensureOpen();
            Path normalized = directory.normalize().toAbsolutePath();
            if (displacedDirectories.containsKey(normalized) ||
                    !Files.exists(normalized,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(normalized) ||
                    !Files.isDirectory(normalized,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException(
                        "World import target is not a safe directory: "
                                + normalized);
            }
            Path backup = nextBackupPath("directories");
            moveWithAtomicFallback(normalized, backup);
            displacedDirectories.put(normalized, backup);
        }

        void commit() {
            ensureOpen();
            closed = true;
            try {
                deleteRecursively(backupRoot);
            } catch (IOException cleanupFailure) {
                Logger.warn("Imported content successfully, but failed to "
                        + "remove transaction backup " + backupRoot + ": "
                        + cleanupFailure.getMessage());
            }
        }

        void rollback(Throwable originalFailure) throws IOException {
            ensureOpen();
            closed = true;
            IOException rollbackFailure = null;

            for (Path created : reverseByDepth(createdFiles)) {
                rollbackFailure = collectFailure(
                        rollbackFailure,
                        () -> Files.deleteIfExists(created));
            }
            for (Map.Entry<Path, Path> backup :
                    fileBackups.entrySet()) {
                rollbackFailure = collectFailure(
                        rollbackFailure,
                        () -> copyFileAtomicallyWithRetry(
                                backup.getValue(),
                                backup.getKey()));
            }
            for (Path created : reverseByDepth(createdDirectories)) {
                rollbackFailure = collectFailure(
                        rollbackFailure,
                        () -> Files.deleteIfExists(created));
            }
            List<Map.Entry<Path, Path>> displaced =
                    new ArrayList<>(displacedDirectories.entrySet());
            Collections.reverse(displaced);
            for (Map.Entry<Path, Path> backup : displaced) {
                rollbackFailure = collectFailure(
                        rollbackFailure,
                        () -> {
                            if (Files.exists(
                                    backup.getKey(),
                                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                                deleteRecursively(backup.getKey());
                            }
                            Path parent = backup.getKey().getParent();
                            if (parent != null) Files.createDirectories(parent);
                            moveWithAtomicFallback(
                                    backup.getValue(),
                                    backup.getKey());
                        });
            }
            rollbackFailure = collectFailure(
                    rollbackFailure,
                    () -> deleteRecursively(backupRoot));
            if (rollbackFailure != null) {
                rollbackFailure.addSuppressed(originalFailure);
                throw rollbackFailure;
            }
        }

        private Path nextBackupPath(String kind) throws IOException {
            Path directory = backupRoot.resolve(kind);
            Files.createDirectories(directory);
            return directory.resolve(
                    String.format(Locale.ROOT, "%08d", nextBackupId++));
        }

        private void ensureOpen() {
            if (closed) {
                throw new IllegalStateException(
                        "Import transaction is already closed.");
            }
        }

        private static List<Path> reverseByDepth(
                Collection<Path> paths) {
            return paths.stream()
                    .sorted(Comparator
                            .comparingInt(Path::getNameCount)
                            .reversed())
                    .toList();
        }

        private static IOException collectFailure(
                IOException existing,
                IoOperation operation) {
            try {
                operation.run();
                return existing;
            } catch (IOException failure) {
                if (existing == null) return failure;
                existing.addSuppressed(failure);
                return existing;
            }
        }

        private static void moveWithAtomicFallback(
                Path source,
                Path destination) throws IOException {
            try {
                Files.move(
                        source,
                        destination,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(source, destination);
            }
        }

        private static void deleteRecursively(Path root)
                throws IOException {
            if (!Files.exists(
                    root,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                return;
            }
            if (Files.isSymbolicLink(root) ||
                    !Files.isDirectory(
                            root,
                            java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
                Files.deleteIfExists(root);
                return;
            }
            try (Stream<Path> paths = Files.walk(root)) {
                for (Path path : paths
                        .sorted(Comparator.reverseOrder())
                        .toList()) {
                    Files.deleteIfExists(path);
                }
            }
        }

        @FunctionalInterface
        private interface IoOperation {
            void run() throws IOException;
        }
    }

    private record ImportCandidate(File source, Path targetPath) {
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

    /**
     * Public because consumers need to know whether FreeMinecraftModels' model registry was
     * invalidated during this startup. When this importer installs models it fires
     * {@link ModelInstallationEvent}, which makes FreeMinecraftModels rebuild its registry
     * asynchronously; a plugin that spawns modelled entities right after importing would find
     * no models and silently drop them. Consumers check this flag to re-wait for
     * FreeMinecraftModels only on the boots where an install actually happened.
     *
     * @return true if this importer moved models into FreeMinecraftModels during this run
     */
    public boolean isModelsInstalled() {
        return modelsInstalled;
    }

    public boolean isEliteMobsContentImported() {
        return eliteMobsContentImported;
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

        String modelsDirectory = ConfigurationImportRegistry.resolveFmmModelsFolder(this).toFile().getAbsolutePath();
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
