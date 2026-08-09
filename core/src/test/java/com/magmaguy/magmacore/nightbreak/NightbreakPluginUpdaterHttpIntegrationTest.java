package com.magmaguy.magmacore.nightbreak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Server;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightbreakPluginUpdaterHttpIntegrationTest {
    private static final String TOKEN = "fixture-token";
    private static final String FIXTURE_MAIN =
            NightbreakPluginUpdaterHttpIntegrationTest.FixtureMain.class.getName();
    private static final NightbreakPluginSpec RSPM_SPEC = new NightbreakPluginSpec(
            "ResourcePackManager", "resourcepackmanager", "resourcepackmanager.*",
            "resourcepackmanager.setup", "resourcepackmanager.initialize",
            "", "Reloaded ResourcePackManager.",
            false, false, false);

    private JavaPlugin plugin;
    private HttpServer httpServer;
    private NightbreakAccount.ScopedTestBaseUrl baseUrlScope;
    private NightbreakPluginUpdater.ScopedSpigotTestBaseUrl spigotBaseUrlScope;
    private final AtomicReference<String> authorizationHeader = new AtomicReference<>();
    private final AtomicInteger versionRequests = new AtomicInteger();
    private final AtomicInteger downloadRequests = new AtomicInteger();

    private static final class TestPlugin extends JavaPlugin {
        private TestPlugin(JavaPluginLoader loader,
                           PluginDescriptionFile description,
                           File dataFolder,
                           File file) {
            super(loader, description, dataFolder, file);
        }
    }

    public static final class FixtureMain extends JavaPlugin {
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        Logger serverLogger = Logger.getLogger("NightbreakPluginUpdaterHttpIntegrationTest");
        Server server = (Server) java.lang.reflect.Proxy.newProxyInstance(
                Server.class.getClassLoader(),
                new Class<?>[]{Server.class},
                (proxy, method, args) -> switch (method.getName()) {
                    case "getLogger" -> serverLogger;
                    case "getName" -> "TestServer";
                    case "getVersion", "getBukkitVersion" -> "test";
                    default -> throw new UnsupportedOperationException(
                            "Server." + method.getName() + " is not stubbed for this test");
                });

        Path pluginsDirectory = tempDir.resolve("plugins");
        Path dataDirectory = pluginsDirectory.resolve("ResourcePackManager");
        Files.createDirectories(dataDirectory);
        plugin = new TestPlugin(
                new JavaPluginLoader(server),
                new PluginDescriptionFile(
                        "ResourcePackManager", "2.3.0", FIXTURE_MAIN),
                dataDirectory.toFile(),
                pluginsDirectory.resolve("ResourcePackManager.jar").toFile());
        NightbreakAccount.registerToken(plugin, TOKEN);
    }

    @AfterEach
    void tearDown() {
        if (spigotBaseUrlScope != null) {
            spigotBaseUrlScope.close();
            spigotBaseUrlScope = null;
        }
        if (baseUrlScope != null) {
            baseUrlScope.close();
            baseUrlScope = null;
        }
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
        NightbreakPluginUpdater.shutdown(plugin);
        NightbreakAccount.resetForTests();
    }

    @Test
    void downloadsAuthenticatedChecksumVerifiedUpdateAndNotifiesListener() throws Exception {
        byte[] artifact = pluginJar(
                "ResourcePackManager", "2.4.0",
                FIXTURE_MAIN, true);
        startFixture(artifact, sha256(artifact), 200);

        AtomicInteger listenerCalls = new AtomicInteger();
        AtomicReference<NightbreakPluginUpdater.PluginUpdateDownload> observed = new AtomicReference<>();
        NightbreakPluginUpdater.PluginUpdateDownload result;
        try (NightbreakPluginUpdater.ListenerRegistration failingListener =
                     NightbreakPluginUpdater.onPluginUpdateDownloaded(plugin, download -> {
                         throw new IllegalStateException("fixture listener failure");
                     });
             NightbreakPluginUpdater.ListenerRegistration recordingListener =
                     NightbreakPluginUpdater.onPluginUpdateDownloaded(plugin, download -> {
                         listenerCalls.incrementAndGet();
                         observed.set(download);
                     })) {
            result = NightbreakPluginUpdater.downloadPluginUpdate(plugin, RSPM_SPEC, null);
        }

        Path updateJar = updateJar();
        assertEquals(NightbreakPluginUpdater.DownloadStatus.DOWNLOADED, result.status());
        assertEquals("2.3.0", result.localVersion());
        assertEquals("2.4.0", result.remoteVersion());
        assertEquals(updateJar.toFile(), result.downloadedFile());
        assertArrayEquals(artifact, Files.readAllBytes(updateJar));
        assertNoTemporaryDownloads();
        assertEquals("Bearer " + TOKEN, authorizationHeader.get());
        assertEquals(1, versionRequests.get());
        assertEquals(1, downloadRequests.get());
        assertEquals(1, listenerCalls.get());
        assertSame(result, observed.get());
    }

    @Test
    void mapsForbiddenDownloadWithoutLeavingFilesOrNotifying() throws Exception {
        byte[] artifact = "denied-artifact".getBytes(StandardCharsets.UTF_8);
        startFixture(artifact, sha256(artifact), 403);
        AtomicInteger listenerCalls = new AtomicInteger();

        NightbreakPluginUpdater.PluginUpdateDownload result;
        try (NightbreakPluginUpdater.ListenerRegistration ignored =
                     NightbreakPluginUpdater.onPluginUpdateDownloaded(plugin, download -> listenerCalls.incrementAndGet())) {
            result = NightbreakPluginUpdater.downloadPluginUpdate(plugin, RSPM_SPEC, null);
        }

        assertEquals(NightbreakPluginUpdater.DownloadStatus.NO_ACCESS, result.status());
        assertEquals("fixture denied", result.detail());
        assertEquals("Bearer " + TOKEN, authorizationHeader.get());
        assertFalse(Files.exists(updateJar()));
        assertNoTemporaryDownloads();
        assertEquals(0, listenerCalls.get());
    }

    @Test
    void checksumFailureDeletesStaleAndDownloadedTemporaryFiles() throws Exception {
        byte[] artifact = "tampered-artifact".getBytes(StandardCharsets.UTF_8);
        startFixture(artifact, "0".repeat(64), 200);
        Files.createDirectories(downloadTempFile().getParent());
        Files.writeString(downloadTempFile(), "stale partial download", StandardCharsets.UTF_8);
        AtomicInteger listenerCalls = new AtomicInteger();

        NightbreakPluginUpdater.PluginUpdateDownload result;
        try (NightbreakPluginUpdater.ListenerRegistration ignored =
                     NightbreakPluginUpdater.onPluginUpdateDownloaded(plugin, download -> listenerCalls.incrementAndGet())) {
            result = NightbreakPluginUpdater.downloadPluginUpdate(plugin, RSPM_SPEC, null);
        }

        assertEquals(NightbreakPluginUpdater.DownloadStatus.CHECKSUM_FAILED, result.status());
        assertFalse(Files.exists(updateJar()));
        assertNoTemporaryDownloads();
        assertEquals(0, listenerCalls.get());
    }

    @Test
    void reloadGenerationCancelsOldDownloadBeforePublication() throws Exception {
        byte[] artifact = pluginJar(
                "ResourcePackManager", "2.4.0",
                FIXTURE_MAIN, true);
        CountDownLatch downloadStarted = new CountDownLatch(1);
        CountDownLatch releaseDownload = new CountDownLatch(1);
        startBlockingFixture(artifact, downloadStarted, releaseDownload);

        AtomicInteger oldListenerCalls = new AtomicInteger();
        AtomicInteger newListenerCalls = new AtomicInteger();
        NightbreakPluginUpdater.ListenerRegistration oldRegistration =
                NightbreakPluginUpdater.onPluginUpdateDownloaded(plugin, download -> oldListenerCalls.incrementAndGet());

        CompletableFuture<NightbreakPluginUpdater.PluginUpdateDownload> download = CompletableFuture.supplyAsync(
                () -> NightbreakPluginUpdater.downloadPluginUpdate(plugin, RSPM_SPEC, null));
        assertTrue(downloadStarted.await(10, TimeUnit.SECONDS), "fixture never received the download request");

        NightbreakPluginUpdater.shutdown(plugin);
        NightbreakPluginUpdater.ListenerRegistration newRegistration =
                NightbreakPluginUpdater.onPluginUpdateDownloaded(plugin, ignored -> newListenerCalls.incrementAndGet());
        releaseDownload.countDown();
        NightbreakPluginUpdater.PluginUpdateDownload result = download.get(10, TimeUnit.SECONDS);

        oldRegistration.close();
        newRegistration.close();
        assertEquals(NightbreakPluginUpdater.DownloadStatus.DOWNLOAD_FAILED, result.status());
        assertEquals(
                "Plugin lifecycle changed before the downloaded update could be published.",
                result.detail());
        assertEquals(0, oldListenerCalls.get());
        assertEquals(0, newListenerCalls.get(), "a pre-reload download must not reach the new generation");
        assertFalse(Files.exists(updateJar()),
                "a pre-reload download must not publish a Bukkit update without its companion listener");
        assertNoTemporaryDownloads();
    }

    @Test
    void concurrentRequestsShareTheSingleDownloadGateAndLeaveNoPartialFile()
            throws Exception {
        byte[] artifact = pluginJar(
                "ResourcePackManager", "2.4.0",
                FIXTURE_MAIN, true);
        CountDownLatch downloadStarted = new CountDownLatch(1);
        CountDownLatch releaseDownload = new CountDownLatch(1);
        startBlockingFixture(artifact, downloadStarted, releaseDownload);

        CompletableFuture<NightbreakPluginUpdater.PluginUpdateDownload> first =
                CompletableFuture.supplyAsync(() ->
                        NightbreakPluginUpdater.downloadPluginUpdate(
                                plugin,
                                RSPM_SPEC,
                                null));
        assertTrue(
                downloadStarted.await(10, TimeUnit.SECONDS),
                "fixture never received the first download request");

        NightbreakPluginUpdater.PluginUpdateDownload duplicate =
                NightbreakPluginUpdater.downloadPluginUpdate(
                        plugin,
                        RSPM_SPEC,
                        null);
        assertEquals(
                NightbreakPluginUpdater.DownloadStatus.DOWNLOAD_FAILED,
                duplicate.status());
        assertEquals(
                "A plugin update download is already in progress.",
                duplicate.detail());

        releaseDownload.countDown();
        NightbreakPluginUpdater.PluginUpdateDownload completed =
                first.get(10, TimeUnit.SECONDS);
        assertEquals(
                NightbreakPluginUpdater.DownloadStatus.DOWNLOADED,
                completed.status());
        assertEquals(1, downloadRequests.get());
        assertTrue(Files.isRegularFile(updateJar()));
        assertNoTemporaryDownloads();
    }

    @Test
    void testBaseUrlRejectsNonLoopbackHosts() {
        assertThrows(IllegalArgumentException.class,
                () -> NightbreakAccount.useLoopbackBaseUrlForTests(URI.create("https://nightbreak.io")));
        assertThrows(IllegalArgumentException.class,
                () -> NightbreakAccount.useLoopbackBaseUrlForTests(URI.create("http://127.example:8080")));
        assertNull(baseUrlScope);
    }

    @Test
    void matchingChecksumCannotPublishCorruptJar() throws Exception {
        byte[] artifact = "not-a-plugin-jar".getBytes(StandardCharsets.UTF_8);
        startFixture(artifact, sha256(artifact), 200);
        AtomicInteger listenerCalls = new AtomicInteger();

        NightbreakPluginUpdater.PluginUpdateDownload result;
        try (NightbreakPluginUpdater.ListenerRegistration ignored =
                     NightbreakPluginUpdater.onPluginUpdateDownloaded(
                             plugin, download -> listenerCalls.incrementAndGet())) {
            result = NightbreakPluginUpdater.downloadPluginUpdate(
                    plugin, RSPM_SPEC, null);
        }

        assertEquals(NightbreakPluginUpdater.DownloadStatus.DOWNLOAD_FAILED,
                result.status());
        assertTrue(result.detail().contains("valid plugin JAR"));
        assertFalse(Files.exists(updateJar()));
        assertNoTemporaryDownloads();
        assertEquals(0, listenerCalls.get());
    }

    @Test
    void invalidArtifactDoesNotReplaceAlreadyStagedValidJar() throws Exception {
        byte[] alreadyStaged = pluginJar(
                "ResourcePackManager", "2.4.0",
                FIXTURE_MAIN, true);
        Files.createDirectories(updateJar().getParent());
        Files.write(updateJar(), alreadyStaged);
        byte[] invalidDownload =
                "not-a-plugin-jar".getBytes(StandardCharsets.UTF_8);
        startFixture(invalidDownload, sha256(invalidDownload), 200);

        NightbreakPluginUpdater.PluginUpdateDownload result =
                NightbreakPluginUpdater.downloadPluginUpdate(
                        plugin, RSPM_SPEC, null);

        assertEquals(NightbreakPluginUpdater.DownloadStatus.DOWNLOAD_FAILED,
                result.status());
        assertArrayEquals(alreadyStaged, Files.readAllBytes(updateJar()));
        assertNoTemporaryDownloads();
    }

    @Test
    void malformedPluginDescriptorIsRejectedAsControlledFailure()
            throws Exception {
        byte[] artifact = jarWithDescriptor("name: [unterminated\n");
        startFixture(artifact, sha256(artifact), 200);

        NightbreakPluginUpdater.PluginUpdateDownload result =
                NightbreakPluginUpdater.downloadPluginUpdate(
                        plugin, RSPM_SPEC, null);

        assertEquals(NightbreakPluginUpdater.DownloadStatus.DOWNLOAD_FAILED,
                result.status());
        assertTrue(result.detail().contains("plugin.yml is invalid"),
                result.detail());
        assertFalse(Files.exists(updateJar()));
        assertNoTemporaryDownloads();
    }

    @Test
    void rejectsWrongPluginIdentityVersionAndMissingMainClass() throws Exception {
        assertInvalidJar(pluginJar(
                "DifferentPlugin", "2.4.0",
                FIXTURE_MAIN, true), "identity");
        restartFixtureState();
        assertInvalidJar(pluginJar(
                "ResourcePackManager", "9.9.9",
                FIXTURE_MAIN, true), "version");
        restartFixtureState();
        assertInvalidJar(pluginJar(
                "ResourcePackManager", "2.4.0-SNAPSHOT",
                FIXTURE_MAIN, true), "version");
        restartFixtureState();
        assertInvalidJar(pluginJar(
                "ResourcePackManager", "2.4.0",
                FIXTURE_MAIN, false), "main class");
    }

    @Test
    void rejectsIncompleteOrInconsistentRspmUniversalDescriptors()
            throws Exception {
        assertInvalidJar(pluginJarWithRspmMutation(
                        "ResourcePackManager", "2.4.0",
                        FIXTURE_MAIN, true,
                        "extension.yml", "2.4.0", true),
                "universal platform descriptor");
        restartFixtureState();
        assertInvalidJar(pluginJarWithRspmMutation(
                        "ResourcePackManager", "2.4.0",
                        FIXTURE_MAIN, true,
                        null, "9.9.9", true),
                "versions disagree");
        restartFixtureState();
        assertInvalidJar(pluginJarWithRspmMutation(
                        "ResourcePackManager", "2.4.0",
                        FIXTURE_MAIN, true,
                        null, "2.4.0", false),
                "entrypoint");
    }

    @Test
    void spigotFallbackUsesOnlyScopedLoopbackOrigin() throws Exception {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext(
                "/server/plugins/resourcepackmanager/version",
                exchange -> writeResponse(exchange, 200, "application/json",
                        "{}".getBytes(StandardCharsets.UTF_8)));
        httpServer.createContext("/legacy/update.php", exchange ->
                writeResponse(exchange, 200, "text/plain",
                        "2.4.0".getBytes(StandardCharsets.UTF_8)));
        httpServer.start();
        URI origin = URI.create(
                "http://127.0.0.1:" + httpServer.getAddress().getPort());
        baseUrlScope = NightbreakAccount.useLoopbackBaseUrlForTests(origin);
        spigotBaseUrlScope =
                NightbreakPluginUpdater.useLoopbackSpigotBaseUrlForTests(origin);

        NightbreakPluginUpdater.PluginUpdateCheck check =
                NightbreakPluginUpdater.checkForUpdate(
                        plugin, RSPM_SPEC, "12345");

        assertFalse(check.nightbreakReachable());
        assertTrue(check.spigotFallbackUsed());
        assertTrue(check.updateAvailable());
        assertEquals("2.4.0", check.remoteVersion());
        assertThrows(IllegalArgumentException.class,
                () -> NightbreakPluginUpdater.useLoopbackSpigotBaseUrlForTests(
                        URI.create("https://api.spigotmc.org")));
    }

    @Test
    void scopedOriginsNeverFollowRedirects() throws Exception {
        AtomicInteger escapedRequests = new AtomicInteger();
        HttpServer redirectTarget =
                HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        redirectTarget.createContext("/", exchange -> {
            escapedRequests.incrementAndGet();
            writeResponse(exchange, 200, "text/plain",
                    "9.9.9".getBytes(StandardCharsets.UTF_8));
        });
        redirectTarget.start();
        try {
            String redirectLocation = "http://127.0.0.1:" +
                    redirectTarget.getAddress().getPort() + "/escaped";
            httpServer =
                    HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            httpServer.createContext(
                    "/server/plugins/redirected/version", exchange -> {
                        exchange.getResponseHeaders().set(
                                "Location", redirectLocation);
                        exchange.sendResponseHeaders(302, -1);
                        exchange.close();
                    });
            httpServer.createContext(
                    "/server/plugins/resourcepackmanager/version",
                    exchange -> writeResponse(
                            exchange, 200, "application/json",
                            "{}".getBytes(StandardCharsets.UTF_8)));
            httpServer.createContext("/legacy/update.php", exchange -> {
                exchange.getResponseHeaders().set(
                        "Location", redirectLocation);
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            });
            httpServer.start();
            URI origin = URI.create(
                    "http://127.0.0.1:" +
                            httpServer.getAddress().getPort());
            baseUrlScope =
                    NightbreakAccount.useLoopbackBaseUrlForTests(origin);
            spigotBaseUrlScope =
                    NightbreakPluginUpdater
                            .useLoopbackSpigotBaseUrlForTests(origin);

            assertNull(NightbreakAccount.getPublicPluginVersion(
                    "redirected", false));
            NightbreakPluginUpdater.PluginUpdateCheck check =
                    NightbreakPluginUpdater.checkForUpdate(
                            plugin, RSPM_SPEC, "12345");
            assertFalse(check.spigotFallbackUsed());
            assertNull(check.remoteVersion());
            assertEquals(0, escapedRequests.get());
        } finally {
            redirectTarget.stop(0);
        }
    }

    @Test
    void clearTokenRemovesSharedCredentialAndAuthFailureState() throws Exception {
        assertTrue(NightbreakAccount.hasToken());
        Path configFile = plugin.getDataFolder().toPath().getParent()
                .resolve("MagmaCore").resolve("nightbreak.yml");
        assertTrue(Files.isRegularFile(configFile));

        assertTrue(NightbreakAccount.clearToken(plugin));

        assertFalse(NightbreakAccount.hasToken());
        assertFalse(NightbreakAccount.hasAuthFailure());
        assertFalse(Files.exists(configFile));
    }

    @Test
    void removableTokenListenerSeesReplacementAndLogoutExactlyOnce() {
        AtomicInteger calls = new AtomicInteger();
        NightbreakAccount.TokenChangeListenerRegistration registration =
                NightbreakAccount.registerTokenChangeListener(
                        calls::incrementAndGet);

        NightbreakAccount.registerToken(plugin, "replacement-token");
        NightbreakAccount.registerToken(plugin, "replacement-token");
        assertTrue(NightbreakAccount.clearToken(plugin));

        assertEquals(2, calls.get(),
                "replacement and logout are distinct credential changes");

        registration.close();
        registration.close();
        NightbreakAccount.registerToken(plugin, "post-close-token");
        assertEquals(2, calls.get(),
                "closed listeners must not survive reload/disable cleanup");
    }

    @Test
    void tokenWritesAreValidatedAndAdvanceTheCrossClassloaderVersionStamp()
            throws Exception {
        Path configFile = plugin.getDataFolder().toPath().getParent()
                .resolve("MagmaCore").resolve("nightbreak.yml");
        long originalMtime =
                Files.getLastModifiedTime(configFile).toMillis();

        assertTrue(NightbreakAccount.registerToken(
                plugin,
                " replacement-token ") != null);
        long replacementMtime =
                Files.getLastModifiedTime(configFile).toMillis();
        assertTrue(
                replacementMtime > originalMtime,
                "every credential write must be observable by other shaded classloaders");
        assertTrue(NightbreakAccount.registerToken(
                plugin,
                "second-token") != null);
        assertTrue(
                Files.getLastModifiedTime(configFile).toMillis() >
                        replacementMtime);

        assertNull(NightbreakAccount.registerToken(plugin, "  "));
        assertNull(NightbreakAccount.registerToken(
                plugin,
                "YOUR_TOKEN_HERE"));
        assertTrue(
                NightbreakAccount.hasToken(),
                "an invalid replacement must not erase the valid credential");
    }

    @Test
    void unsafeRemoteFilenameIsRejectedBeforeDownload() throws Exception {
        byte[] artifact = pluginJar(
                "ResourcePackManager", "2.4.0", FIXTURE_MAIN, true);
        startFixture(artifact, sha256(artifact), 200, "../escape.jar");

        NightbreakPluginUpdater.PluginUpdateDownload result =
                NightbreakPluginUpdater.downloadPluginUpdate(
                        plugin, RSPM_SPEC, null);

        assertEquals(NightbreakPluginUpdater.DownloadStatus.DOWNLOAD_FAILED,
                result.status());
        assertTrue(result.detail().contains("unsafe plugin filename"));
        assertEquals(0, downloadRequests.get());
        assertFalse(Files.exists(updateJar()));
        assertFalse(Files.exists(
                plugin.getDataFolder().toPath().getParent()
                        .resolve("escape.jar")));
    }

    private void assertInvalidJar(byte[] artifact, String expectedDetail)
            throws Exception {
        startFixture(artifact, sha256(artifact), 200);
        NightbreakPluginUpdater.PluginUpdateDownload result =
                NightbreakPluginUpdater.downloadPluginUpdate(
                        plugin, RSPM_SPEC, null);
        assertEquals(NightbreakPluginUpdater.DownloadStatus.DOWNLOAD_FAILED,
                result.status());
        assertTrue(result.detail().toLowerCase().contains(expectedDetail));
        assertFalse(Files.exists(updateJar()));
        assertNoTemporaryDownloads();
    }

    private void restartFixtureState() {
        if (baseUrlScope != null) {
            baseUrlScope.close();
            baseUrlScope = null;
        }
        if (httpServer != null) {
            httpServer.stop(0);
            httpServer = null;
        }
    }

    private void startFixture(byte[] artifact, String checksum, int downloadStatus) throws IOException {
        startFixture(
                artifact, checksum, downloadStatus,
                "ResourcePackManager.jar");
    }

    private void startFixture(byte[] artifact,
                              String checksum,
                              int downloadStatus,
                              String remoteFileName) throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/server/plugins/resourcepackmanager/version", exchange -> {
            versionRequests.incrementAndGet();
            byte[] response = ("{\"slug\":\"resourcepackmanager\",\"version\":\"2.4.0\","
                    + "\"fileName\":\"" + remoteFileName +
                    "\",\"checksum\":\"" + checksum + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            writeResponse(exchange, 200, "application/json", response);
        });
        httpServer.createContext("/server/plugins/resourcepackmanager/download", exchange -> {
            downloadRequests.incrementAndGet();
            authorizationHeader.set(exchange.getRequestHeaders().getFirst("Authorization"));
            if (downloadStatus == 200) {
                writeResponse(exchange, 200, "application/java-archive", artifact);
            } else {
                writeResponse(exchange, downloadStatus, "application/json",
                        "{\"error\":\"NO_ACCESS\",\"reason\":\"fixture denied\"}"
                                .getBytes(StandardCharsets.UTF_8));
            }
        });
        startAndScopeFixture();
    }

    private void startBlockingFixture(byte[] artifact,
                                      CountDownLatch downloadStarted,
                                      CountDownLatch releaseDownload) throws IOException {
        String checksum = sha256(artifact);
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/server/plugins/resourcepackmanager/version", exchange -> {
            byte[] response = ("{\"slug\":\"resourcepackmanager\",\"version\":\"2.4.0\","
                    + "\"fileName\":\"ResourcePackManager.jar\",\"checksum\":\"" + checksum + "\"}")
                    .getBytes(StandardCharsets.UTF_8);
            writeResponse(exchange, 200, "application/json", response);
        });
        httpServer.createContext("/server/plugins/resourcepackmanager/download", exchange -> {
            downloadRequests.incrementAndGet();
            downloadStarted.countDown();
            try {
                if (!releaseDownload.await(10, TimeUnit.SECONDS)) {
                    writeResponse(exchange, 500, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                    return;
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                writeResponse(exchange, 500, "application/json", "{}".getBytes(StandardCharsets.UTF_8));
                return;
            }
            writeResponse(exchange, 200, "application/java-archive", artifact);
        });
        startAndScopeFixture();
    }

    private void startAndScopeFixture() {
        httpServer.start();
        baseUrlScope = NightbreakAccount.useLoopbackBaseUrlForTests(
                URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort()));
    }

    private static void writeResponse(HttpExchange exchange,
                                      int status,
                                      String contentType,
                                      byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(status, body.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private Path updateJar() {
        return plugin.getDataFolder().toPath().getParent().resolve("update").resolve("ResourcePackManager.jar");
    }

    private Path downloadTempFile() {
        return updateJar().resolveSibling("ResourcePackManager.jar.download");
    }

    private void assertNoTemporaryDownloads() throws IOException {
        Path updateDirectory = updateJar().getParent();
        if (!Files.isDirectory(updateDirectory)) return;
        try (var files = Files.list(updateDirectory)) {
            assertEquals(
                    0L,
                    files.filter(path ->
                                    path.getFileName().toString()
                                            .endsWith(".download"))
                            .count(),
                    "temporary update downloads must be cleaned");
        }
    }

    private static String sha256(byte[] bytes) {
        try {
            byte[] hash = MessageDigest.getInstance("SHA-256").digest(bytes);
            StringBuilder value = new StringBuilder();
            for (byte element : hash) {
                value.append(String.format("%02x", element));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    private static byte[] pluginJar(String name,
                                    String version,
                                    String mainClass,
                                    boolean includeMainClass) throws IOException {
        return pluginJarWithRspmMutation(
                name,
                version,
                mainClass,
                includeMainClass,
                null,
                version,
                true);
    }

    private static byte[] pluginJarWithRspmMutation(
            String name,
            String version,
            String mainClass,
            boolean includeMainClass,
            String omittedDescriptor,
            String velocityVersion,
            boolean includeVelocityMainClass) throws IOException {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            jar.write(("name: " + name + "\n" +
                    "version: " + version + "\n" +
                    "main: " + mainClass + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            if (includeMainClass) {
                writeFixtureClass(jar, mainClass);
            }
            if ("ResourcePackManager".equals(name)) {
                String bungeeMain =
                        "fixture.platform.RspmBungeePlugin";
                String geyserMain =
                        "fixture.platform.RspmGeyserExtension";
                String velocityMain =
                        "fixture.platform.RspmVelocityPlugin";
                if (!"bungee.yml".equals(omittedDescriptor)) {
                    writeJarEntry(jar, "bungee.yml",
                            "name: ResourcePackManager\n"
                                    + "version: " + version + "\n"
                                    + "main: " + bungeeMain + "\n");
                }
                if (!"extension.yml".equals(omittedDescriptor)) {
                    writeJarEntry(jar, "extension.yml",
                            "name: ResourcePackManagerGeyserBridge\n"
                                    + "version: " + version + "\n"
                                    + "main: " + geyserMain + "\n");
                }
                if (!"velocity-plugin.json".equals(omittedDescriptor)) {
                    writeJarEntry(jar, "velocity-plugin.json",
                            "{\"id\":\"resourcepackmanager\","
                                    + "\"name\":\"ResourcePackManager\","
                                    + "\"version\":\"" + velocityVersion + "\","
                                    + "\"main\":\"" + velocityMain + "\"}");
                }
                writeFixtureClass(jar, bungeeMain);
                writeFixtureClass(jar, geyserMain);
                if (includeVelocityMainClass) {
                    writeFixtureClass(jar, velocityMain);
                }
            }
        }
        return bytes.toByteArray();
    }

    private static void writeJarEntry(
            JarOutputStream jar, String name, String source)
            throws IOException {
        jar.putNextEntry(new JarEntry(name));
        jar.write(source.getBytes(StandardCharsets.UTF_8));
        jar.closeEntry();
    }

    private static void writeFixtureClass(
            JarOutputStream jar, String entrypoint) throws IOException {
        jar.putNextEntry(new JarEntry(
                entrypoint.replace('.', '/') + ".class"));
        String resource = "/" + FIXTURE_MAIN.replace('.', '/') + ".class";
        try (var input =
                     NightbreakPluginUpdaterHttpIntegrationTest.class
                             .getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException(
                        "Missing fixture class resource " + resource);
            }
            input.transferTo(jar);
        }
        jar.closeEntry();
    }

    private static byte[] jarWithDescriptor(String descriptor)
            throws IOException {
        java.io.ByteArrayOutputStream bytes =
                new java.io.ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            jar.write(descriptor.getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }
}
