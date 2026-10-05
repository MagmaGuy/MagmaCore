package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

public final class NightbreakPluginInstaller {
    private static final Set<String> RUNNING_INSTALLS =
            ConcurrentHashMap.newKeySet();

    private NightbreakPluginInstaller() {
    }

    public enum InstallStatus {
        DOWNLOADED,
        ALREADY_INSTALLED,
        NO_TOKEN,
        AUTH_FAILURE,
        NO_ACCESS,
        REMOTE_UNREACHABLE,
        DOWNLOAD_FAILED,
        CHECKSUM_FAILED,
        EXTERNAL_ONLY
    }

    public record InstallResult(InstallStatus status,
                                NightbreakPluginCatalog.Entry entry,
                                String remoteVersion,
                                File downloadedFile,
                                String detail) {
    }

    public static void downloadPluginAsync(JavaPlugin ownerPlugin,
                                           NightbreakPluginCatalog.Entry entry,
                                           CommandSender sender,
                                           Consumer<InstallResult> callback) {
        long generation =
                NightbreakPluginUpdater.lifecycleGeneration(ownerPlugin);
        NightbreakPluginAsyncWorkTracker.Work work =
                NightbreakPluginUpdater.registerAsyncWork(ownerPlugin, generation);
        if (work == null) return;
        try {
            var task = Bukkit.getScheduler().runTaskAsynchronously(ownerPlugin, () -> {
                if (!work.start()) return;
                try {
                    if (!NightbreakPluginUpdater.isLifecycleCurrent(ownerPlugin, generation)) return;
                    InstallResult result = downloadPlugin(ownerPlugin, entry, sender, generation);
                    if (!NightbreakPluginUpdater.isLifecycleCurrent(ownerPlugin, generation)) return;
                    Bukkit.getScheduler().runTask(ownerPlugin, () -> {
                        if (!NightbreakPluginUpdater.isLifecycleCurrent(ownerPlugin, generation)) return;
                        sendInstallResult(sender, result);
                        if (callback != null) callback.accept(result);
                    });
                } finally {
                    work.close();
                }
            });
            work.attach(task);
        } catch (RuntimeException exception) {
            work.dispatchFailed();
            throw exception;
        }
    }

    private static InstallResult downloadPlugin(JavaPlugin ownerPlugin,
                                                NightbreakPluginCatalog.Entry entry,
                                                CommandSender sender,
                                                long generation) {
        String runningKey = ownerPlugin.getName()
                .toLowerCase(Locale.ROOT) + ":" + entry.slug();
        if (!RUNNING_INSTALLS.add(runningKey)) {
            return new InstallResult(
                    InstallStatus.DOWNLOAD_FAILED,
                    entry,
                    null,
                    null,
                    "An install for this plugin is already in progress.");
        }
        try {
            return performDownloadPlugin(
                    ownerPlugin, entry, sender, generation);
        } finally {
            RUNNING_INSTALLS.remove(runningKey);
        }
    }

    private static InstallResult performDownloadPlugin(
            JavaPlugin ownerPlugin,
            NightbreakPluginCatalog.Entry entry,
            CommandSender sender,
            long generation) {
        if (!NightbreakPluginUpdater.isLifecycleCurrent(
                ownerPlugin, generation)) {
            return new InstallResult(
                    InstallStatus.DOWNLOAD_FAILED,
                    entry,
                    null,
                    null,
                    "Plugin lifecycle changed before the install started.");
        }
        if (entry.sourceType() == NightbreakPluginCatalog.SourceType.EXTERNAL) {
            return new InstallResult(InstallStatus.EXTERNAL_ONLY, entry, null, null, null);
        }
        if (NightbreakPluginCatalog.isInstalled(entry)) {
            return new InstallResult(InstallStatus.ALREADY_INSTALLED, entry, null, null, null);
        }
        if (!NightbreakAccount.hasToken()) {
            return new InstallResult(InstallStatus.NO_TOKEN, entry, null, null, null);
        }
        if (NightbreakAccount.hasAuthFailure()) {
            return new InstallResult(InstallStatus.AUTH_FAILURE, entry, null, null, null);
        }

        NightbreakAccount.VersionInfo versionInfo = NightbreakAccount.getPublicPluginVersion(entry.slug());
        if (versionInfo == null || versionInfo.version == null || versionInfo.version.isBlank()) {
            return new InstallResult(InstallStatus.REMOTE_UNREACHABLE, entry, null, null, null);
        }

        NightbreakAccount account = NightbreakAccount.getInstance();
        if (account == null) {
            return new InstallResult(InstallStatus.NO_TOKEN, entry, versionInfo.version, null, null);
        }

        String targetFileName = targetFileName(entry, versionInfo.fileName);
        if (targetFileName == null) {
            return new InstallResult(InstallStatus.DOWNLOAD_FAILED, entry,
                    versionInfo.version, null,
                    "The update service returned an unsafe plugin filename.");
        }
        File pluginsFolder = ownerPlugin.getDataFolder().getParentFile();
        if (pluginsFolder == null) {
            return new InstallResult(InstallStatus.DOWNLOAD_FAILED, entry, versionInfo.version, null,
                    "Could not find the plugins folder.");
        }
        // Bukkit and Paper load a jar from the update folder only to replace a jar already in
        // the plugins folder. A plugin with no jar there goes straight into the plugins folder.
        File existingJar = findExistingJar(pluginsFolder, targetFileName, entry.allPluginNames());
        boolean newInstall = existingJar == null;
        File destinationFolder = newInstall ? pluginsFolder : resolveUpdateFolder(ownerPlugin);
        String destinationName = newInstall ? targetFileName : existingJar.getName();
        if (!destinationFolder.exists() && !destinationFolder.mkdirs()) {
            return new InstallResult(InstallStatus.DOWNLOAD_FAILED, entry, versionInfo.version, null,
                    "Could not create folder: " + destinationFolder.getPath());
        }

        File tempFile = new File(
                destinationFolder,
                destinationName + "." + generation + "."
                        + UUID.randomUUID() + ".download");
        File targetFile = new File(destinationFolder, destinationName);
        File legacyTempFile =
                new File(destinationFolder, destinationName + ".download");
        if (legacyTempFile.exists() && !legacyTempFile.delete()) {
            return new InstallResult(
                    InstallStatus.DOWNLOAD_FAILED,
                    entry,
                    versionInfo.version,
                    null,
                    "Could not clear a legacy temporary download.");
        }

        final long[] lastProgressMessage = {0L};
        NightbreakAccount.PluginDownloadResult downloadResult = account.downloadPluginUpdate(entry.slug(), tempFile, (bytesDownloaded, totalBytes) -> {
            if (!NightbreakPluginUpdater.isLifecycleCurrent(ownerPlugin, generation)) return;
            if (!canMessage(sender)) return;
            long now = System.currentTimeMillis();
            if (now - lastProgressMessage[0] < 2000L) return;
            lastProgressMessage[0] = now;
            String progress = totalBytes > 0
                    ? String.format(Locale.ROOT, "%.1f%%", bytesDownloaded * 100.0 / totalBytes)
                    : formatBytes(bytesDownloaded);
            Bukkit.getScheduler().runTask(ownerPlugin, () -> {
                if (NightbreakPluginUpdater.isLifecycleCurrent(ownerPlugin, generation) && canMessage(sender)) {
                    Logger.sendSimpleMessage(sender, "&7[" + entry.displayName() + "] Downloading plugin... " + progress);
                }
            });
        }, () -> NightbreakPluginUpdater.isLifecycleCurrent(ownerPlugin, generation));

        if (!downloadResult.success || !tempFile.exists()) {
            if (tempFile.exists()) tempFile.delete();
            return new InstallResult(statusFor(downloadResult), entry, versionInfo.version, null, downloadResult.displayDetail());
        }

        if (!NightbreakPluginUpdater.isLifecycleCurrent(ownerPlugin, generation)) {
            tempFile.delete();
            return new InstallResult(InstallStatus.DOWNLOAD_FAILED, entry, versionInfo.version, null,
                    "Plugin lifecycle changed before the install could be validated.");
        }

        if (versionInfo.checksum != null && !versionInfo.checksum.isBlank()) {
            String actualChecksum;
            try {
                actualChecksum = sha256(tempFile);
            } catch (IllegalStateException exception) {
                tempFile.delete();
                return new InstallResult(InstallStatus.CHECKSUM_FAILED, entry, versionInfo.version, null, exception.getMessage());
            }
            if (!versionInfo.checksum.equalsIgnoreCase(actualChecksum)) {
                tempFile.delete();
                return new InstallResult(InstallStatus.CHECKSUM_FAILED, entry, versionInfo.version, null,
                        "Expected " + versionInfo.checksum + ", got " + actualChecksum);
            }
        }

        DownloadedPluginJarValidator.ValidationResult jarValidation =
                DownloadedPluginJarValidator.validate(
                        tempFile, entry.allPluginNames(), versionInfo.version);
        if (!jarValidation.valid()) {
            tempFile.delete();
            return new InstallResult(InstallStatus.DOWNLOAD_FAILED, entry,
                    versionInfo.version, null, jarValidation.detail());
        }

        try {
            boolean published =
                    NightbreakPluginUpdater.publishIfLifecycleCurrent(
                            ownerPlugin,
                            generation,
                            () -> {
                                if (newInstall) moveWithoutReplacing(tempFile, targetFile);
                                else moveReplacing(tempFile, targetFile);
                            });
            if (!published) {
                tempFile.delete();
                return new InstallResult(
                        InstallStatus.DOWNLOAD_FAILED,
                        entry,
                        versionInfo.version,
                        null,
                        "Plugin lifecycle changed before the install could be published.");
            }
        } catch (FileAlreadyExistsException exception) {
            tempFile.delete();
            return new InstallResult(InstallStatus.DOWNLOAD_FAILED, entry, versionInfo.version, null,
                    targetFile.getName() + " appeared in the plugins folder during the download and was left unchanged.");
        } catch (IOException exception) {
            tempFile.delete();
            return new InstallResult(InstallStatus.DOWNLOAD_FAILED, entry, versionInfo.version, null, exception.getMessage());
        }

        return new InstallResult(InstallStatus.DOWNLOADED, entry, versionInfo.version, targetFile, null);
    }

    private static void sendInstallResult(CommandSender sender, InstallResult result) {
        if (!canMessage(sender)) return;
        NightbreakPluginCatalog.Entry entry = result.entry();
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
        switch (result.status()) {
            case DOWNLOADED -> {
                Logger.sendSimpleMessage(sender, "<g:#77dd77:#d4ff9d>" + entry.displayName() + " downloaded</g>&7.");
                if (result.remoteVersion() != null) {
                    Logger.sendSimpleMessage(sender, "&7Version: &a" + result.remoteVersion());
                }
                Logger.sendSimpleMessage(sender, "&7Restart the server to use it.");
            }
            case ALREADY_INSTALLED -> Logger.sendSimpleMessage(sender, "&a" + entry.displayName() + " is already installed.");
            case NO_TOKEN -> {
                Logger.sendSimpleMessage(sender, "&eConnect this server before installing " + entry.displayName() + " from in-game.");
                sendAccountAndManualLinks(sender, entry);
            }
            case AUTH_FAILURE -> {
                NightbreakSetupMenuHelper.sendTokenUpdatePrompt(sender, entry.displayName());
                sendManualDownload(sender, entry);
            }
            case NO_ACCESS -> {
                Logger.sendSimpleMessage(sender, "&eThe server declined this in-game install for &f" + entry.displayName() + "&e.");
                Logger.sendSimpleMessage(sender, "&7In-game plugin installs are a supporter convenience.");
                if (result.detail() != null && !result.detail().isBlank()) {
                    Logger.sendSimpleMessage(sender, "&8Remote says: &7" + result.detail());
                }
                sendManualDownload(sender, entry);
            }
            case REMOTE_UNREACHABLE -> {
                Logger.sendSimpleMessage(sender, "&cThe plugin download service could not be reached.");
                sendManualDownload(sender, entry);
            }
            case CHECKSUM_FAILED -> {
                Logger.sendSimpleMessage(sender, "&cThe downloaded plugin failed checksum verification.");
                Logger.sendSimpleMessage(sender, "&7The file was not kept. Please use the manual page or try again later.");
                sendManualDownload(sender, entry);
            }
            case DOWNLOAD_FAILED -> {
                Logger.sendSimpleMessage(sender, "&cThe in-game plugin install could not be completed.");
                if (result.detail() != null && !result.detail().isBlank()) {
                    Logger.sendSimpleMessage(sender, "&8Details: &7" + result.detail());
                }
                sendManualDownload(sender, entry);
            }
            case EXTERNAL_ONLY -> {
                Logger.sendSimpleMessage(sender, "&e" + entry.displayName() + " is hosted externally.");
                sendManualDownload(sender, entry);
            }
        }
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
    }

    private static void sendAccountAndManualLinks(CommandSender sender, NightbreakPluginCatalog.Entry entry) {
        sendLink(sender, "&7Account token: ", "&9&nhttps://nightbreak.io/account/",
                "&7Click to open the account token page.", "https://nightbreak.io/account/");
        if (sender instanceof Player player) {
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage("&7Link command: "),
                    SpigotMessage.commandHoverMessage("&a/nightbreaklogin <token>",
                            "&7Click to prepare the token command.",
                            "/nightbreaklogin "));
        } else {
            Logger.sendSimpleMessage(sender, "&7Link command: &a/nightbreaklogin <token>");
        }
        sendManualDownload(sender, entry);
    }

    private static void sendManualDownload(CommandSender sender, NightbreakPluginCatalog.Entry entry) {
        sendLink(sender, "&7Manual download page: ", "&9&n" + entry.downloadPageUrl(),
                "&7Click to open the public download page.", entry.downloadPageUrl());
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

    private static InstallStatus statusFor(NightbreakAccount.PluginDownloadResult downloadResult) {
        if (downloadResult.responseCode == 401 || NightbreakAccount.hasAuthFailure()) {
            return InstallStatus.AUTH_FAILURE;
        }
        if ("NO_TOKEN".equalsIgnoreCase(downloadResult.error)) {
            return InstallStatus.NO_TOKEN;
        }
        if (downloadResult.responseCode == 403) {
            return InstallStatus.NO_ACCESS;
        }
        if (downloadResult.responseCode == 0 || "NETWORK_ERROR".equalsIgnoreCase(downloadResult.error)) {
            return InstallStatus.REMOTE_UNREACHABLE;
        }
        return InstallStatus.DOWNLOAD_FAILED;
    }

    private static String targetFileName(NightbreakPluginCatalog.Entry entry, String remoteFileName) {
        String catalogFileName = safeJarFileName(entry.jarFileName());
        if (catalogFileName != null) return catalogFileName;
        if (remoteFileName != null && !remoteFileName.isBlank()) {
            return safeJarFileName(remoteFileName);
        }
        return safeJarFileName(
                entry.displayName().replace(" ", "") + ".jar");
    }

    private static String safeJarFileName(String candidate) {
        String value = candidate == null ? "" : candidate.trim();
        if (value.isBlank() ||
                !value.toLowerCase(Locale.ROOT).endsWith(".jar") ||
                value.indexOf('/') >= 0 ||
                value.indexOf('\\') >= 0 ||
                value.indexOf(':') >= 0 ||
                value.indexOf('\0') >= 0 ||
                ".".equals(value) ||
                "..".equals(value)) {
            return null;
        }
        return value;
    }

    private static File resolveUpdateFolder(JavaPlugin plugin) {
        try {
            Object updateFolder = Bukkit.getServer().getClass().getMethod("getUpdateFolderFile").invoke(Bukkit.getServer());
            if (updateFolder instanceof File file) return file;
        } catch (ReflectiveOperationException ignored) {
        }
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        return new File(pluginsFolder, "update");
    }

    /**
     * The jar already in the plugins folder for this entry: its catalog filename, or any jar
     * that declares one of its plugin names. Null when the plugin has no jar there.
     */
    private static File findExistingJar(File pluginsFolder, String catalogFileName, List<String> pluginNames) {
        File catalogJar = new File(pluginsFolder, catalogFileName);
        if (catalogJar.exists()) return catalogJar;
        File[] jars = pluginsFolder.listFiles((folder, name) -> name.toLowerCase(Locale.ROOT).endsWith(".jar"));
        if (jars == null) return null;
        for (File jar : jars) {
            String declaredName = declaredPluginName(jar);
            if (declaredName == null) continue;
            for (String pluginName : pluginNames) {
                // Bukkit stores plugin names with spaces replaced by underscores.
                if (declaredName.equalsIgnoreCase(pluginName.replace(' ', '_'))) return jar;
            }
        }
        return null;
    }

    private static String declaredPluginName(File jar) {
        if (!jar.isFile()) return null;
        try (JarFile jarFile = new JarFile(jar)) {
            JarEntry descriptor = jarFile.getJarEntry("plugin.yml");
            if (descriptor == null) return null;
            try (InputStream input = jarFile.getInputStream(descriptor)) {
                return new PluginDescriptionFile(input).getName();
            }
        } catch (IOException | InvalidDescriptionException | RuntimeException exception) {
            return null;
        }
    }

    /**
     * Publishes a download under a name the plugins folder does not have. A hard link appears
     * at once and fails if the name exists, whereas ATOMIC_MOVE replaces the target on Windows
     * and Linux.
     */
    private static void moveWithoutReplacing(File source, File target) throws IOException {
        Path sourcePath = source.toPath();
        Path targetPath = target.toPath();
        try {
            Files.createLink(targetPath, sourcePath);
        } catch (FileAlreadyExistsException exists) {
            throw exists;
        } catch (UnsupportedOperationException | IOException linkFailed) {
            // Without hard links, a move without REPLACE_EXISTING still refuses an existing target.
            Files.move(sourcePath, targetPath);
            return;
        }
        try {
            Files.deleteIfExists(sourcePath);
        } catch (IOException ignored) {
            // The jar is published. A leftover .download file is not loaded as a plugin.
            source.deleteOnExit();
        }
    }

    private static void moveReplacing(File source, File target) throws IOException {
        try {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (IOException ignored) {
            Files.move(source.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
        }
    }

    private static String sha256(File file) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (FileInputStream inputStream = new FileInputStream(file)) {
                byte[] buffer = new byte[8192];
                int read;
                while ((read = inputStream.read(buffer)) != -1) {
                    digest.update(buffer, 0, read);
                }
            }
            StringBuilder builder = new StringBuilder();
            for (byte b : digest.digest()) {
                builder.append(String.format("%02x", b));
            }
            return builder.toString();
        } catch (IOException | NoSuchAlgorithmException exception) {
            throw new IllegalStateException("Could not calculate checksum", exception);
        }
    }

    private static boolean canMessage(CommandSender sender) {
        return sender != null && (!(sender instanceof Player player) || player.isOnline());
    }

    private static String formatBytes(long bytes) {
        if (bytes < 1024) return bytes + " B";
        if (bytes < 1024 * 1024) return String.format(Locale.ROOT, "%.1f KB", bytes / 1024.0);
        return String.format(Locale.ROOT, "%.1f MB", bytes / (1024.0 * 1024.0));
    }
}
