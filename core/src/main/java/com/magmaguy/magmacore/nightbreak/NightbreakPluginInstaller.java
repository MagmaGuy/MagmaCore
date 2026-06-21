package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.Bukkit;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Locale;
import java.util.function.Consumer;

public final class NightbreakPluginInstaller {
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
        Bukkit.getScheduler().runTaskAsynchronously(ownerPlugin, () -> {
            InstallResult result = downloadPlugin(ownerPlugin, entry, sender);
            Bukkit.getScheduler().runTask(ownerPlugin, () -> {
                sendInstallResult(sender, result);
                if (callback != null) callback.accept(result);
            });
        });
    }

    private static InstallResult downloadPlugin(JavaPlugin ownerPlugin,
                                                NightbreakPluginCatalog.Entry entry,
                                                CommandSender sender) {
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

        File updateFolder = resolveUpdateFolder(ownerPlugin);
        if (!updateFolder.exists() && !updateFolder.mkdirs()) {
            return new InstallResult(InstallStatus.DOWNLOAD_FAILED, entry, versionInfo.version, null,
                    "Could not create update folder: " + updateFolder.getPath());
        }

        String targetFileName = targetFileName(entry, versionInfo.fileName);
        File tempFile = new File(updateFolder, targetFileName + ".download");
        File targetFile = new File(updateFolder, targetFileName);
        if (tempFile.exists() && !tempFile.delete()) {
            return new InstallResult(InstallStatus.DOWNLOAD_FAILED, entry, versionInfo.version, null,
                    "Could not clear previous temporary download.");
        }

        final long[] lastProgressMessage = {0L};
        NightbreakAccount.PluginDownloadResult downloadResult = account.downloadPluginUpdate(entry.slug(), tempFile, (bytesDownloaded, totalBytes) -> {
            if (!canMessage(sender)) return;
            long now = System.currentTimeMillis();
            if (now - lastProgressMessage[0] < 2000L) return;
            lastProgressMessage[0] = now;
            String progress = totalBytes > 0
                    ? String.format(Locale.ROOT, "%.1f%%", bytesDownloaded * 100.0 / totalBytes)
                    : formatBytes(bytesDownloaded);
            Bukkit.getScheduler().runTask(ownerPlugin, () -> {
                if (canMessage(sender)) {
                    Logger.sendSimpleMessage(sender, "&7[" + entry.displayName() + "] Downloading plugin... " + progress);
                }
            });
        });

        if (!downloadResult.success || !tempFile.exists()) {
            if (tempFile.exists()) tempFile.delete();
            return new InstallResult(statusFor(downloadResult), entry, versionInfo.version, null, downloadResult.displayDetail());
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

        try {
            moveReplacing(tempFile, targetFile);
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
        if (remoteFileName != null && remoteFileName.endsWith(".jar")) return remoteFileName;
        if (!entry.jarFileName().isBlank()) return entry.jarFileName();
        return entry.displayName().replace(" ", "") + ".jar";
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
