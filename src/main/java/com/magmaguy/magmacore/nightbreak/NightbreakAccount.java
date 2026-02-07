package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.util.Logger;
import lombok.Getter;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.Scanner;

/**
 * Manages the Nightbreak account token and provides access to the Nightbreak DLC API.
 * <p>
 * API Base URL: https://nightbreak.io
 * <p>
 * Endpoints:
 * - GET /server/dlc/:slug/version - Get DLC version info (no auth required)
 * - GET /server/dlc/:slug/access - Check if user has access (auth required)
 * - GET /server/dlc/:slug/download - Download DLC file (auth required)
 */
public class NightbreakAccount {
    private static final String BASE_URL = "https://nightbreak.io";
    private static final String CONFIG_FOLDER_NAME = "MagmaCore";
    private static final String CONFIG_FILE_NAME = "nightbreak.yml";

    @Getter
    private static NightbreakAccount instance;
    @Getter
    private String token;

    private NightbreakAccount(String token) {
        this.token = token;
        instance = this;
    }

    /**
     * Initializes the NightbreakAccount by loading the token from the shared config folder.
     * Should be called during plugin startup.
     *
     * @param plugin The plugin instance to get the data folder reference
     * @return The NightbreakAccount instance if token exists, null otherwise
     */
    public static NightbreakAccount initialize(JavaPlugin plugin) {
        File configFile = getConfigFile(plugin);
        if (!configFile.exists()) {
            Logger.info("No Nightbreak token found. Use /nightbreakLogin <token> to register your token.");
            return null;
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        String token = config.getString("token");

        if (token == null || token.isEmpty() || token.equals("YOUR_TOKEN_HERE")) {
            Logger.info("No Nightbreak token configured. Use /nightbreakLogin <token> to register your token.");
            return null;
        }

        instance = new NightbreakAccount(token);
        Logger.info("Nightbreak account loaded successfully!");
        return instance;
    }

    /**
     * Registers a new token by saving it to the shared config file.
     *
     * @param plugin The plugin instance
     * @param token  The token to register
     * @return The new NightbreakAccount instance
     */
    public static NightbreakAccount registerToken(JavaPlugin plugin, String token) {
        File configFile = getConfigFile(plugin);

        // Ensure parent directory exists
        if (!configFile.getParentFile().exists()) {
            configFile.getParentFile().mkdirs();
        }

        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);

        // Set the header comment
        config.options().setHeader(java.util.List.of(
                "MagmaCore Shared Configuration",
                "",
                "This folder is a common config folder for plugins created by MagmaGuy, including:",
                "- EliteMobs",
                "- BetterStructures",
                "- FreeMinecraftModels",
                "- BetterFood",
                "- ResourcePackManager",
                "- ExtractionCraft",
                "- EternalTD",
                "- MegaBlock Survivors",
                "- And others!",
                "",
                "This folder can be deleted without causing too many problems,",
                "but you may need to reconfigure shared settings like your Nightbreak token.",
                "",
                "Get your token at: https://nightbreak.io/account"
        ));

        config.set("token", token);

        try {
            config.save(configFile);
        } catch (IOException e) {
            Logger.warn("Failed to save Nightbreak token: " + e.getMessage());
            return null;
        }

        instance = new NightbreakAccount(token);
        return instance;
    }

    /**
     * Gets the shared config file for MagmaCore.
     *
     * @param plugin The plugin instance
     * @return The config file
     */
    private static File getConfigFile(JavaPlugin plugin) {
        // Go to plugins folder (parent of plugin's data folder), then into MagmaCore folder
        File pluginsFolder = plugin.getDataFolder().getParentFile();
        File magmaCoreFolder = new File(pluginsFolder, CONFIG_FOLDER_NAME);
        return new File(magmaCoreFolder, CONFIG_FILE_NAME);
    }

    /**
     * Checks if a token is currently registered.
     *
     * @return true if a token is registered
     */
    public static boolean hasToken() {
        return instance != null && instance.token != null && !instance.token.isEmpty();
    }

    // ==================== API METHODS ====================

    /**
     * Gets the latest version info for a DLC.
     * This endpoint does NOT require authentication.
     *
     * @param slug The DLC slug (e.g., "bone-monastery", "the-dark-cathedral")
     * @return VersionInfo containing version details, or null on error
     */
    public VersionInfo getVersion(String slug) {
        try {
            String url = BASE_URL + "/server/dlc/" + slug + "/version";
            String response = httpGet(url, false);

            if (response == null) return null;

            // Parse JSON response
            // Expected: {"slug":"...","version":"11","versionInt":11,"fileSize":17574333,"fileName":"..."}
            return parseVersionInfo(response);
        } catch (Exception e) {
            Logger.warn("Error getting version for DLC '" + slug + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Gets version info for all available DLC from the Nightbreak API.
     * This endpoint does NOT require authentication.
     *
     * @return Map of slug to VersionInfo, or empty map on error
     */
    public Map<String, VersionInfo> getAllVersions() {
        try {
            String url = BASE_URL + "/api/dlc/versions";
            String response = httpGet(url, false);
            if (response == null) return new HashMap<>();
            return parseAllVersionsResponse(response);
        } catch (Exception e) {
            Logger.warn("Error getting all DLC versions: " + e.getMessage());
            return new HashMap<>();
        }
    }

    /**
     * Checks if the user has access to a DLC.
     * This endpoint REQUIRES authentication.
     *
     * @param slug The DLC slug
     * @return AccessInfo containing access details, or null on error
     */
    public AccessInfo checkAccess(String slug) {
        if (!hasToken()) {
            Logger.warn("Cannot check access: No Nightbreak token registered. Use /nightbreakLogin <token> first.");
            return null;
        }

        try {
            String url = BASE_URL + "/server/dlc/" + slug + "/access";
            String response = httpGet(url, true);

            if (response == null) return null;

            return parseAccessInfo(response);
        } catch (Exception e) {
            Logger.warn("Error checking access for DLC '" + slug + "': " + e.getMessage());
            return null;
        }
    }

    /**
     * Downloads a DLC file.
     * This endpoint REQUIRES authentication.
     *
     * @param slug            The DLC slug
     * @param destinationFile The file to save the download to
     * @return true if download was successful
     */
    public boolean download(String slug, File destinationFile) {
        return download(slug, destinationFile, null);
    }

    /**
     * Downloads a specific version of a DLC file.
     * This endpoint REQUIRES authentication.
     *
     * @param slug            The DLC slug
     * @param destinationFile The file to save the download to
     * @param version         The specific version to download, or null for latest
     * @return true if download was successful
     */
    public boolean download(String slug, File destinationFile, String version) {
        if (!hasToken()) {
            Logger.warn("Cannot download: No Nightbreak token registered. Use /nightbreakLogin <token> first.");
            return false;
        }

        try {
            String url = BASE_URL + "/server/dlc/" + slug + "/download";
            if (version != null) {
                url += "?version=" + version;
            }

            return httpDownload(url, destinationFile);
        } catch (Exception e) {
            Logger.warn("Error downloading DLC '" + slug + "': " + e.getMessage());
            return false;
        }
    }

    /**
     * Downloads a DLC file with progress updates.
     * This endpoint REQUIRES authentication.
     *
     * @param slug            The DLC slug
     * @param destinationFile The file to save the download to
     * @param version         The specific version to download, or null for latest
     * @param progressCallback Callback for progress updates, or null for no updates
     * @return true if download was successful
     */
    public boolean download(String slug, File destinationFile, String version, DownloadProgressCallback progressCallback) {
        if (!hasToken()) {
            Logger.warn("Cannot download: No Nightbreak token registered. Use /nightbreakLogin <token> first.");
            return false;
        }
        try {
            String url = BASE_URL + "/server/dlc/" + slug + "/download";
            if (version != null) {
                url += "?version=" + version;
            }
            return httpDownloadWithProgress(url, destinationFile, progressCallback);
        } catch (Exception e) {
            Logger.warn("Error downloading DLC '" + slug + "': " + e.getMessage());
            return false;
        }
    }

    // ==================== HTTP HELPERS ====================

    private String httpGet(String urlString, boolean withAuth) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");

            if (withAuth && token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }

            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8);
                StringBuilder response = new StringBuilder();
                while (scanner.hasNext()) {
                    response.append(scanner.nextLine());
                }
                scanner.close();
                return response.toString();
            } else {
                // Read error response
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    Scanner scanner = new Scanner(errorStream, StandardCharsets.UTF_8);
                    StringBuilder errorResponse = new StringBuilder();
                    while (scanner.hasNext()) {
                        errorResponse.append(scanner.nextLine());
                    }
                    scanner.close();
                    Logger.warn("API error (" + responseCode + ") for " + urlString + ": " + errorResponse);
                } else {
                    Logger.warn("API error (" + responseCode + ") for " + urlString + " (no error body)");
                }
                return null;
            }
        } catch (IOException e) {
            Logger.warn("HTTP request failed for " + urlString + ": " + e.getMessage());
            return null;
        }
    }

    private boolean httpDownload(String urlString, File destinationFile) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");

            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }

            int responseCode = connection.getResponseCode();

            if (responseCode == 200) {
                // Ensure parent directory exists
                if (!destinationFile.getParentFile().exists()) {
                    destinationFile.getParentFile().mkdirs();
                }

                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(destinationFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                    }
                }
                return true;
            } else {
                // Read error response
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    Scanner scanner = new Scanner(errorStream, StandardCharsets.UTF_8);
                    StringBuilder errorResponse = new StringBuilder();
                    while (scanner.hasNext()) {
                        errorResponse.append(scanner.nextLine());
                    }
                    scanner.close();
                    Logger.warn("Download failed (" + responseCode + ") for " + urlString + ": " + errorResponse);
                } else {
                    Logger.warn("Download failed (" + responseCode + ") for " + urlString + " (no error body)");
                }
                return false;
            }
        } catch (IOException e) {
            Logger.warn("Download failed for " + urlString + ": " + e.getMessage());
            return false;
        }
    }

    private boolean httpDownloadWithProgress(String urlString, File destinationFile, DownloadProgressCallback callback) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            if (token != null) {
                connection.setRequestProperty("Authorization", "Bearer " + token);
            }
            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                if (!destinationFile.getParentFile().exists()) {
                    destinationFile.getParentFile().mkdirs();
                }
                long totalBytes = connection.getContentLengthLong();
                long bytesDownloaded = 0;
                long lastProgressUpdate = 0;
                try (InputStream in = connection.getInputStream();
                     FileOutputStream out = new FileOutputStream(destinationFile)) {
                    byte[] buffer = new byte[8192];
                    int bytesRead;
                    while ((bytesRead = in.read(buffer)) != -1) {
                        out.write(buffer, 0, bytesRead);
                        bytesDownloaded += bytesRead;
                        if (callback != null && (bytesDownloaded - lastProgressUpdate >= 102400 || bytesRead == -1)) {
                            callback.onProgress(bytesDownloaded, totalBytes);
                            lastProgressUpdate = bytesDownloaded;
                        }
                    }
                    if (callback != null) {
                        callback.onProgress(bytesDownloaded, totalBytes);
                    }
                }
                return true;
            } else {
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    Scanner scanner = new Scanner(errorStream, StandardCharsets.UTF_8);
                    StringBuilder errorResponse = new StringBuilder();
                    while (scanner.hasNext()) {
                        errorResponse.append(scanner.nextLine());
                    }
                    scanner.close();
                    Logger.warn("Download failed (" + responseCode + ") for " + urlString + ": " + errorResponse);
                } else {
                    Logger.warn("Download failed (" + responseCode + ") for " + urlString + " (no error body)");
                }
                return false;
            }
        } catch (IOException e) {
            Logger.warn("Download failed for " + urlString + ": " + e.getMessage());
            return false;
        }
    }

    // ==================== JSON PARSING ====================

    private VersionInfo parseVersionInfo(String json) {
        // Simple JSON parsing without external dependencies
        VersionInfo info = new VersionInfo();
        info.slug = extractJsonString(json, "slug");
        info.version = extractJsonString(json, "version");
        info.versionInt = extractJsonInt(json, "versionInt");
        info.fileSize = extractJsonLong(json, "fileSize");
        info.fileName = extractJsonString(json, "fileName");
        return info;
    }

    private AccessInfo parseAccessInfo(String json) {
        AccessInfo info = new AccessInfo();
        info.slug = extractJsonString(json, "slug");
        info.hasAccess = extractJsonBoolean(json, "hasAccess");
        info.accessSource = extractJsonString(json, "accessSource");
        info.reason = extractJsonString(json, "reason");
        info.version = extractJsonString(json, "version");
        info.fileSize = extractJsonLong(json, "fileSize");
        info.requiredTier = extractJsonString(json, "requiredTier");
        info.patreonLink = extractJsonString(json, "patreonLink");
        info.itchLink = extractJsonString(json, "itchLink");
        info.error = extractJsonString(json, "error");
        info.message = extractJsonString(json, "message");
        return info;
    }

    private String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return null;
        startIndex += searchKey.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) return null;
        return json.substring(startIndex, endIndex);
    }

    private int extractJsonInt(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return -1;
        startIndex += searchKey.length();
        int endIndex = startIndex;
        while (endIndex < json.length() && (Character.isDigit(json.charAt(endIndex)) || json.charAt(endIndex) == '-')) {
            endIndex++;
        }
        try {
            return Integer.parseInt(json.substring(startIndex, endIndex));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private long extractJsonLong(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return -1;
        startIndex += searchKey.length();
        int endIndex = startIndex;
        while (endIndex < json.length() && (Character.isDigit(json.charAt(endIndex)) || json.charAt(endIndex) == '-')) {
            endIndex++;
        }
        try {
            return Long.parseLong(json.substring(startIndex, endIndex));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private boolean extractJsonBoolean(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return false;
        startIndex += searchKey.length();
        return json.substring(startIndex).startsWith("true");
    }

    private Map<String, VersionInfo> parseAllVersionsResponse(String json) {
        Map<String, VersionInfo> versions = new HashMap<>();
        int versionsStart = json.indexOf("\"versions\":");
        if (versionsStart == -1) return versions;
        int arrayStart = json.indexOf("[", versionsStart);
        if (arrayStart == -1) return versions;
        int arrayEnd = findMatchingBracket(json, arrayStart, '[', ']');
        if (arrayEnd == -1) return versions;
        String arrayContent = json.substring(arrayStart, arrayEnd + 1);
        int pos = 0;
        while (pos < arrayContent.length()) {
            int objectStart = arrayContent.indexOf("{", pos);
            if (objectStart == -1) break;
            int objectEnd = findMatchingBracket(arrayContent, objectStart, '{', '}');
            if (objectEnd == -1) break;
            String objectJson = arrayContent.substring(objectStart, objectEnd + 1);
            VersionInfo info = parseVersionInfo(objectJson);
            if (info.slug != null) {
                versions.put(info.slug, info);
            }
            pos = objectEnd + 1;
        }
        return versions;
    }

    private int findMatchingBracket(String json, int openPos, char openChar, char closeChar) {
        int depth = 0;
        boolean inString = false;
        for (int i = openPos; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '"' && (i == 0 || json.charAt(i - 1) != '\\')) {
                inString = !inString;
            } else if (!inString) {
                if (c == openChar) depth++;
                else if (c == closeChar) {
                    depth--;
                    if (depth == 0) return i;
                }
            }
        }
        return -1;
    }

    // ==================== DATA CLASSES ====================

    /**
     * Contains version information for a DLC.
     */
    public static class VersionInfo {
        public String slug;
        public String version;
        public int versionInt;
        public long fileSize;
        public String fileName;

        @Override
        public String toString() {
            return "VersionInfo{slug='" + slug + "', version='" + version + "', versionInt=" + versionInt +
                    ", fileSize=" + fileSize + ", fileName='" + fileName + "'}";
        }
    }

    /**
     * Contains access information for a DLC.
     */
    public static class AccessInfo {
        public String slug;
        public boolean hasAccess;
        public String accessSource;
        public String reason;
        public String version;
        public long fileSize;
        public String requiredTier;
        public String patreonLink;
        public String itchLink;
        // Error fields (present when request fails)
        public String error;
        public String message;

        public boolean isError() {
            return error != null && !error.isEmpty();
        }

        @Override
        public String toString() {
            if (isError()) {
                return "AccessInfo{error='" + error + "', message='" + message + "'}";
            }
            return "AccessInfo{slug='" + slug + "', hasAccess=" + hasAccess + ", accessSource='" + accessSource +
                    "', reason='" + reason + "', version='" + version + "', fileSize=" + fileSize +
                    ", patreonLink='" + patreonLink + "', itchLink='" + itchLink + "'}";
        }
    }

    /**
     * Callback interface for download progress updates.
     */
    public interface DownloadProgressCallback {
        /**
         * Called periodically during download with progress info.
         * @param bytesDownloaded Total bytes downloaded so far
         * @param totalBytes Total file size in bytes (-1 if unknown)
         */
        void onProgress(long bytesDownloaded, long totalBytes);
    }
}
