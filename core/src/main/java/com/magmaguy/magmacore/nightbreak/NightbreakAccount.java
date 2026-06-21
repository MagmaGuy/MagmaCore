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
import java.net.URLEncoder;
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

    private static volatile NightbreakAccount instance;
    @Getter
    private String token;

    // Track the shared YAML file's path + mtime so each plugin's shaded copy of
    // NightbreakAccount can detect when another plugin's copy wrote a new token
    // (via /nightbreaklogin) and reload it on the next access. Without this,
    // each consuming plugin's classloader-local `instance` static stays at
    // whatever was on disk at THIS plugin's startup — so changes made by ONE
    // plugin's shaded copy (which is the only one that registers the command)
    // never propagate to the others until a full server restart. Cheap because
    // every access pattern across the ecosystem is occasional (commands,
    // menu opens, 24-hour scheduled checks) — File.lastModified is sub-microsecond.
    private static volatile File configFilePath = null;
    private static volatile long lastFileMtime = -1L;

    private NightbreakAccount(String token) {
        this.token = token;
        instance = this;
    }

    /**
     * Returns the singleton, re-reading from disk if another plugin's shaded
     * copy wrote a new token since the last access. See class-level comment
     * about cross-classloader shading for why this is necessary.
     */
    public static NightbreakAccount getInstance() {
        ensureFresh();
        return instance;
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
        // Remember the path even when the file doesn't exist yet — ensureFresh()
        // needs it so a /nightbreaklogin issued LATER from another plugin's
        // shaded copy can be picked up by THIS plugin's next access.
        configFilePath = configFile;
        if (!configFile.exists()) {
            Logger.info("No account token found. Use /nightbreaklogin <token> to register your token.");
            return null;
        }

        loadTokenFromFile(configFile);
        lastFileMtime = configFile.lastModified();
        if (instance != null) {
            Logger.info("Account token loaded successfully!");
        } else {
            Logger.info("No account token configured. Use /nightbreaklogin <token> to register your token.");
        }
        return instance;
    }

    /**
     * Stats the shared config file and reloads {@link #instance} from disk if the
     * file's mtime changed since the last successful load (or if the file went
     * missing). Synchronized to ensure at most one shaded copy reloads at a time
     * within a given classloader. Cheap: the common case is a single mtime
     * comparison, no I/O beyond that.
     */
    private static synchronized void ensureFresh() {
        File f = configFilePath;
        if (f == null) return; // initialize() hasn't run in this classloader yet
        if (!f.exists()) {
            if (instance != null) {
                instance = null;
                lastFileMtime = -1L;
            }
            return;
        }
        long mtime = f.lastModified();
        if (mtime != lastFileMtime) {
            boolean hadInstanceBefore = instance != null;
            loadTokenFromFile(f);
            lastFileMtime = mtime;
            // If this reload transitioned us from "no token" → "have token",
            // notify subscribers so they can refresh whatever state they gated
            // on having a token. This is the cross-classloader notification path:
            // EliteMobs (which owns /nightbreaklogin) sees the transition via
            // registerToken() in its own classloader; every other plugin shading
            // MagmaCore sees it here, the next time anything in their classloader
            // calls hasToken() / getInstance().
            if (!hadInstanceBefore && instance != null) {
                fireTokenChanged();
            }
        }
    }

    private static void loadTokenFromFile(File configFile) {
        FileConfiguration config = YamlConfiguration.loadConfiguration(configFile);
        String token = config.getString("token");
        if (token != null) token = token.trim();
        if (token == null || token.isEmpty() || token.equals("YOUR_TOKEN_HERE")) {
            instance = null;
            return;
        }
        instance = new NightbreakAccount(token);
        // A new token from disk means the old auth-failure suppression no longer
        // applies — give the new value a fresh chance to log if it's also bad.
        resetAuthFailureSuppression();
    }

    // ─────────────────────────────────────────────────────────────────────
    // Token-change listener registry.
    //
    // Why this exists: prior to this, /nightbreaklogin would save the token to
    // disk but no consumer was notified. Plugins that gated content fetches on
    // hasToken() during their onEnable would never re-fetch after a later
    // login — the user had to restart the server (or /em reload) for content to
    // appear. Now plugins register a Runnable here in their onEnable; it fires
    // exactly when this classloader's view transitions to "have token" (either
    // because registerToken() ran in this classloader, or because ensureFresh()
    // picked up a token a different classloader's command wrote to disk).
    // ─────────────────────────────────────────────────────────────────────
    private static final java.util.List<Runnable> tokenChangeListeners =
            new java.util.concurrent.CopyOnWriteArrayList<>();

    /**
     * Subscribes a callback that fires when this classloader's NightbreakAccount
     * transitions from "no token" to "have token". Fires at most once per such
     * transition; idempotent re-loads of the same token do NOT fire.
     */
    public static void addTokenChangeListener(Runnable listener) {
        if (listener != null) tokenChangeListeners.add(listener);
    }

    private static void fireTokenChanged() {
        for (Runnable listener : tokenChangeListeners) {
            try {
                listener.run();
            } catch (Throwable t) {
                Logger.warn("Account token-change listener threw: " + t.getMessage());
            }
        }
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
        configFilePath = configFile;  // ensure ensureFresh() in this classloader knows where to look

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
                "but you may need to reconfigure shared settings like your account token.",
                "",
                "Get your token at: https://nightbreak.io/account"
        ));

        config.set("token", token);

        try {
            config.save(configFile);
        } catch (IOException e) {
            Logger.warn("Failed to save account token: " + e.getMessage());
            return null;
        }

        boolean hadInstanceBefore = instance != null;
        instance = new NightbreakAccount(token);
        // Pin the mtime to the value we just wrote so ensureFresh() in THIS
        // classloader doesn't see a spurious "changed" on the very next access
        // and reload what we already have in memory. Other plugins' shaded
        // copies will see their own lastFileMtime as stale on their next access
        // and reload from disk — that's the whole point of this design.
        lastFileMtime = configFile.lastModified();
        // Token changed — give the new token a fresh chance to log auth failures.
        resetAuthFailureSuppression();
        // Notify in-classloader subscribers if this was a "no token → have token"
        // transition (the typical /nightbreaklogin flow). Other classloaders'
        // shaded copies will fire their own listeners from ensureFresh() the
        // next time anything in those classloaders touches NightbreakAccount.
        if (!hadInstanceBefore) {
            fireTokenChanged();
        }
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
     * Checks if a token is currently registered. Triggers a lazy mtime-based
     * reload first so a token written by another plugin's shaded copy is
     * picked up without a server restart.
     *
     * @return true if a token is registered
     */
    public static boolean hasToken() {
        ensureFresh();
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
            Logger.warn("Cannot check access: No account token registered. Use /nightbreaklogin <token> first.");
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
            Logger.warn("Cannot download: No account token registered. Use /nightbreaklogin <token> first.");
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
            Logger.warn("Cannot download: No account token registered. Use /nightbreaklogin <token> first.");
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

    public VersionInfo getPluginVersion(String slug) {
        return getPublicPluginVersion(slug);
    }

    public static VersionInfo getPublicPluginVersion(String slug) {
        try {
            String url = BASE_URL + "/server/plugins/" + encodePathSegment(slug) + "/version";
            String response = httpGetPublic(url);
            if (response == null) return null;
            return parseVersionInfo(response);
        } catch (Exception e) {
            Logger.warn("Error getting plugin version for '" + slug + "': " + e.getMessage());
            return null;
        }
    }

    public AccessInfo checkPluginAccess(String slug) {
        if (!hasToken()) {
            Logger.warn("Cannot check plugin update access: No account token registered. Use /nightbreaklogin <token> first.");
            return null;
        }

        try {
            String url = BASE_URL + "/server/plugins/" + encodePathSegment(slug) + "/access";
            String response = httpGet(url, true);
            if (response == null) return null;
            return parseAccessInfo(response);
        } catch (Exception e) {
            Logger.warn("Error checking plugin update access for '" + slug + "': " + e.getMessage());
            return null;
        }
    }

    public boolean downloadPlugin(String slug, File destinationFile, DownloadProgressCallback progressCallback) {
        return downloadPluginUpdate(slug, destinationFile, progressCallback).success;
    }

    public PluginDownloadResult downloadPluginUpdate(String slug, File destinationFile, DownloadProgressCallback progressCallback) {
        if (!hasToken()) {
            Logger.warn("Cannot download plugin update: No account token registered. Use /nightbreaklogin <token> first.");
            return new PluginDownloadResult(false, 0, "NO_TOKEN",
                    "No account token registered.", null, null);
        }

        try {
            String url = BASE_URL + "/server/plugins/" + encodePathSegment(slug) + "/download";
            return httpDownloadWithProgressResult(url, destinationFile, progressCallback);
        } catch (Exception e) {
            Logger.warn("Error downloading plugin update '" + slug + "': " + e.getMessage());
            return new PluginDownloadResult(false, 0, "DOWNLOAD_ERROR",
                    e.getMessage(), null, null);
        }
    }

    // ==================== HTTP HELPERS ====================

    private static final int CONNECT_TIMEOUT_MS = 10000;
    private static final int READ_TIMEOUT_MS = 10000;

    private static String encodePathSegment(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20");
    }

    private static String httpGetPublic(String urlString) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

            int responseCode = connection.getResponseCode();
            if (responseCode == 200) {
                Scanner scanner = new Scanner(connection.getInputStream(), StandardCharsets.UTF_8);
                StringBuilder response = new StringBuilder();
                while (scanner.hasNext()) {
                    response.append(scanner.nextLine());
                }
                scanner.close();
                return response.toString();
            }

            String errorResponse = null;
            InputStream errorStream = connection.getErrorStream();
            if (errorStream != null) {
                Scanner scanner = new Scanner(errorStream, StandardCharsets.UTF_8);
                StringBuilder sb = new StringBuilder();
                while (scanner.hasNext()) {
                    sb.append(scanner.nextLine());
                }
                scanner.close();
                errorResponse = sb.toString();
            }
            logHttpError(urlString, responseCode, errorResponse);
            return null;
        } catch (IOException e) {
            logNetworkFailure(urlString, e.getMessage());
            return null;
        }
    }

    // Spam control for noisy failure modes. With many DLC slugs being checked
    // on startup, a dead token or unreachable remote would otherwise spew one
    // warning per slug.
    //
    // Auth failures (401/403): once seen, suppress further auth-failure logs
    // until the token is replaced — there's no point reporting them per slug.
    // Network failures (IOException): rate-limit to one summary per window;
    // counts of suppressed entries are flushed when the window resets.
    private static volatile boolean suppressAuthFailureLogs = false;
    private static volatile int lastAuthFailureStatus = 0;
    private static volatile String lastAuthFailureBody = null;
    private static volatile long networkFailureWindowStart = 0L;
    private static volatile int networkFailuresInWindow = 0;
    private static volatile String lastNetworkFailureMessage = null;
    private static final long NETWORK_FAILURE_WINDOW_MS = 5L * 60L * 1000L; // 5 min

    private String httpGet(String urlString, boolean withAuth) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setRequestProperty("Accept", "application/json");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

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
                // Read error body (always — caller may want to inspect it).
                String errorResponse = null;
                InputStream errorStream = connection.getErrorStream();
                if (errorStream != null) {
                    Scanner scanner = new Scanner(errorStream, StandardCharsets.UTF_8);
                    StringBuilder sb = new StringBuilder();
                    while (scanner.hasNext()) {
                        sb.append(scanner.nextLine());
                    }
                    scanner.close();
                    errorResponse = sb.toString();
                }
                logHttpError(urlString, responseCode, errorResponse);
                return null;
            }
        } catch (IOException e) {
            logNetworkFailure(urlString, e.getMessage());
            return null;
        }
    }

    private static void logHttpError(String urlString, int responseCode, String errorResponse) {
        // 401/403: token issue. Log the FIRST one with full body so the user
        // sees what went wrong; suppress everything that follows until the
        // token is replaced via registerToken() (which clears the flag).
        if (responseCode == 401 || responseCode == 403) {
            lastAuthFailureStatus = responseCode;
            lastAuthFailureBody = errorResponse;
            if (suppressAuthFailureLogs) return;
            suppressAuthFailureLogs = true;
            String body = errorResponse != null ? errorResponse : "(no body)";
            Logger.warn("The account token was rejected (" + responseCode + ") for " + urlString
                    + ": " + body
                    + ". Get a new token at https://nightbreak.io/account/ and run "
                    + "/nightbreaklogin <token>, then try again. Further token errors will be hidden until the token changes.");
            return;
        }
        // Other HTTP errors are rare enough to log every time.
        if (errorResponse != null) {
            Logger.warn("Nightbreak API error (" + responseCode + ") for " + urlString + ": " + errorResponse);
        } else {
            Logger.warn("Nightbreak API error (" + responseCode + ") for " + urlString + " (no error body)");
        }
    }

    private static void logNetworkFailure(String urlString, String message) {
        long now = System.currentTimeMillis();
        synchronized (NightbreakAccount.class) {
            if (now - networkFailureWindowStart > NETWORK_FAILURE_WINDOW_MS) {
                // New window — flush prior suppressed count and start fresh.
                if (networkFailuresInWindow > 1) {
                    Logger.warn("Nightbreak: " + (networkFailuresInWindow - 1)
                            + " more network failures suppressed in the previous window. "
                            + "Last error: " + lastNetworkFailureMessage);
                }
                networkFailureWindowStart = now;
                networkFailuresInWindow = 1;
                lastNetworkFailureMessage = message;
                Logger.warn("Nightbreak unreachable for " + urlString + ": " + message
                        + " — further network failures will be summarized once per "
                        + (NETWORK_FAILURE_WINDOW_MS / 60000) + " minutes.");
            } else {
                networkFailuresInWindow++;
                lastNetworkFailureMessage = message;
            }
        }
    }

    /** Re-enables auth-failure logging. Call after a token is replaced. */
    public static void resetAuthFailureSuppression() {
        suppressAuthFailureLogs = false;
        lastAuthFailureStatus = 0;
        lastAuthFailureBody = null;
    }

    /**
     * Returns true after Nightbreak rejects the current token with 401/403.
     * Cleared when a new token is loaded or registered.
     */
    public static boolean hasAuthFailure() {
        return suppressAuthFailureLogs;
    }

    public static int getLastAuthFailureStatus() {
        return lastAuthFailureStatus;
    }

    public static String getLastAuthFailureBody() {
        return lastAuthFailureBody;
    }

    private boolean httpDownload(String urlString, File destinationFile) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);

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
                } catch (IOException e) {
                    // Clean up partial file on failure
                    if (destinationFile.exists()) destinationFile.delete();
                    throw e;
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
            // Clean up partial file on failure
            if (destinationFile.exists()) destinationFile.delete();
            Logger.warn("Download failed for " + urlString + ": " + e.getMessage());
            return false;
        }
    }

    private boolean httpDownloadWithProgress(String urlString, File destinationFile, DownloadProgressCallback callback) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
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
                } catch (IOException e) {
                    // Clean up partial file on failure
                    if (destinationFile.exists()) destinationFile.delete();
                    throw e;
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
            // Clean up partial file on failure
            if (destinationFile.exists()) destinationFile.delete();
            Logger.warn("Download failed for " + urlString + ": " + e.getMessage());
            return false;
        }
    }

    private PluginDownloadResult httpDownloadWithProgressResult(String urlString, File destinationFile, DownloadProgressCallback callback) {
        try {
            URL url = new URL(urlString);
            HttpURLConnection connection = (HttpURLConnection) url.openConnection();
            connection.setRequestMethod("GET");
            connection.setConnectTimeout(CONNECT_TIMEOUT_MS);
            connection.setReadTimeout(READ_TIMEOUT_MS);
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
                        if (callback != null && bytesDownloaded - lastProgressUpdate >= 102400) {
                            callback.onProgress(bytesDownloaded, totalBytes);
                            lastProgressUpdate = bytesDownloaded;
                        }
                    }
                    if (callback != null) {
                        callback.onProgress(bytesDownloaded, totalBytes);
                    }
                } catch (IOException e) {
                    if (destinationFile.exists()) destinationFile.delete();
                    throw e;
                }
                return new PluginDownloadResult(true, responseCode, null, null, null, null);
            }

            String errorResponse = readErrorResponse(connection);
            if (responseCode == 401) {
                logHttpError(urlString, responseCode, errorResponse);
            } else if (responseCode >= 500) {
                Logger.warn("Nightbreak plugin update download failed (" + responseCode + ") for " + urlString
                        + (errorResponse == null ? "" : ": " + errorResponse));
            }
            return PluginDownloadResult.fromHttp(responseCode, errorResponse);
        } catch (IOException e) {
            if (destinationFile.exists()) destinationFile.delete();
            logNetworkFailure(urlString, e.getMessage());
            return new PluginDownloadResult(false, 0, "NETWORK_ERROR", e.getMessage(), null, null);
        }
    }

    private static String readErrorResponse(HttpURLConnection connection) throws IOException {
        InputStream errorStream = connection.getErrorStream();
        if (errorStream == null) return null;
        Scanner scanner = new Scanner(errorStream, StandardCharsets.UTF_8);
        StringBuilder errorResponse = new StringBuilder();
        while (scanner.hasNext()) {
            errorResponse.append(scanner.nextLine());
        }
        scanner.close();
        return errorResponse.toString();
    }

    // ==================== JSON PARSING ====================

    private static VersionInfo parseVersionInfo(String json) {
        // Simple JSON parsing without external dependencies
        VersionInfo info = new VersionInfo();
        info.slug = extractJsonString(json, "slug");
        info.version = extractJsonString(json, "version");
        if (info.version == null) info.version = extractJsonString(json, "currentVersion");
        info.versionInt = extractJsonInt(json, "versionInt");
        if (info.versionInt < 0) info.versionInt = parseIntegerVersion(info.version);
        info.fileSize = extractJsonLong(json, "fileSize");
        info.fileName = extractJsonString(json, "fileName");
        info.checksum = extractJsonString(json, "checksum");
        info.changelog = extractJsonString(json, "changelog");
        info.downloadPageUrl = extractJsonString(json, "downloadPageUrl");
        return info;
    }

    private static int parseIntegerVersion(String version) {
        if (version == null) return -1;
        String normalized = version.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        if (!normalized.matches("\\d+")) return -1;
        try {
            return Integer.parseInt(normalized);
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static AccessInfo parseAccessInfo(String json) {
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

    private static String extractJsonString(String json, String key) {
        String searchKey = "\"" + key + "\":\"";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return null;
        startIndex += searchKey.length();
        int endIndex = json.indexOf("\"", startIndex);
        if (endIndex == -1) return null;
        return json.substring(startIndex, endIndex);
    }

    private static int extractJsonInt(String json, String key) {
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

    private static long extractJsonLong(String json, String key) {
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

    private static boolean extractJsonBoolean(String json, String key) {
        String searchKey = "\"" + key + "\":";
        int startIndex = json.indexOf(searchKey);
        if (startIndex == -1) return false;
        startIndex += searchKey.length();
        return json.substring(startIndex).startsWith("true");
    }

    private static Map<String, VersionInfo> parseAllVersionsResponse(String json) {
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

    private static int findMatchingBracket(String json, int openPos, char openChar, char closeChar) {
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
        public String checksum;
        public String changelog;
        public String downloadPageUrl;

        @Override
        public String toString() {
            return "VersionInfo{slug='" + slug + "', version='" + version + "', versionInt=" + versionInt +
                    ", fileSize=" + fileSize + ", fileName='" + fileName + "', checksum='" + checksum + "'}";
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

    public static class PluginDownloadResult {
        public final boolean success;
        public final int responseCode;
        public final String error;
        public final String message;
        public final String reason;
        public final String requiredTier;

        public PluginDownloadResult(boolean success,
                                    int responseCode,
                                    String error,
                                    String message,
                                    String reason,
                                    String requiredTier) {
            this.success = success;
            this.responseCode = responseCode;
            this.error = error;
            this.message = message;
            this.reason = reason;
            this.requiredTier = requiredTier;
        }

        private static PluginDownloadResult fromHttp(int responseCode, String json) {
            String error = json == null ? null : extractJsonString(json, "error");
            String message = json == null ? null : extractJsonString(json, "message");
            String reason = json == null ? null : extractJsonString(json, "reason");
            String requiredTier = json == null ? null : extractJsonString(json, "requiredTier");
            return new PluginDownloadResult(false, responseCode, error, message, reason, requiredTier);
        }

        public String displayDetail() {
            if (reason != null && !reason.isBlank()) return reason;
            if (message != null && !message.isBlank()) return message;
            if (error != null && !error.isBlank()) return error;
            if (responseCode > 0) return "Nightbreak returned HTTP " + responseCode + ".";
            return null;
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
