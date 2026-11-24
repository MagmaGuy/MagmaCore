package com.magmaguy.magmacore.nightbreak;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.bukkit.Bukkit;
import org.bukkit.Server;
import org.bukkit.command.ConsoleCommandSender;
import org.bukkit.plugin.PluginDescriptionFile;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.plugin.java.JavaPluginLoader;
import org.bukkit.scheduler.BukkitScheduler;
import org.bukkit.scheduler.BukkitTask;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.mockito.MockedStatic;

import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.logging.Logger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.mockStatic;

/**
 * The automatic plugin and content downloads repeat every 24 hours. Each test takes the
 * repeating task the updater schedules and runs it once per simulated day against a
 * loopback update service, counting the downloads that reach it.
 */
class NightbreakAutoUpdateRepeatTest {
    private static final long ONE_DAY_TICKS = 20L * 60L * 60L * 24L;
    private static final String FIXTURE_MAIN = FixtureMain.class.getName();
    private static final NightbreakPluginSpec SPEC = new NightbreakPluginSpec(
            "FreeMinecraftModels", "freeminecraftmodels", "freeminecraftmodels.*",
            "freeminecraftmodels.setup", "freeminecraftmodels.initialize",
            "", "Reloaded FreeMinecraftModels.",
            true, false, false);

    private TestPlugin plugin;
    private HttpServer httpServer;
    private NightbreakAccount.ScopedTestBaseUrl baseUrlScope;
    private volatile String pluginVersion = "2.4.0";
    private volatile byte[] pluginArtifact;
    private volatile int contentVersion = 2;
    private final AtomicInteger pluginVersionRequests = new AtomicInteger();
    private final AtomicInteger pluginDownloadRequests = new AtomicInteger();
    private final AtomicInteger contentDownloadRequests = new AtomicInteger();

    public static final class FixtureMain extends JavaPlugin {
    }

    private static final class TestPlugin extends JavaPlugin {
        private TestPlugin(JavaPluginLoader loader, PluginDescriptionFile description, File dataFolder, File file) {
            super(loader, description, dataFolder, file);
        }

        private void enable() {
            setEnabled(true);
        }
    }

    @BeforeEach
    void setUp(@TempDir Path tempDir) throws IOException {
        Logger serverLogger = Logger.getLogger("NightbreakAutoUpdateRepeatTest");
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
        Path dataDirectory = pluginsDirectory.resolve("FreeMinecraftModels");
        Files.createDirectories(dataDirectory);
        plugin = new TestPlugin(
                new JavaPluginLoader(server),
                new PluginDescriptionFile("FreeMinecraftModels", "2.3.0", FIXTURE_MAIN),
                dataDirectory.toFile(),
                pluginsDirectory.resolve("FreeMinecraftModels.jar").toFile());
        plugin.enable();
        NightbreakAccount.registerToken(plugin, "fixture-token");
        pluginArtifact = pluginJar("2.4.0");
        startFixture();
        setAutomaticUpdates(true);
    }

    @AfterEach
    void tearDown() {
        if (baseUrlScope != null) baseUrlScope.close();
        if (httpServer != null) httpServer.stop(0);
        // Shutdown cancels the scheduled tasks, so it needs a scheduler to talk to.
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            stubBukkit(bukkit, null);
            NightbreakPluginUpdater.shutdown(plugin);
            NightbreakBulkDownloader.shutdown(plugin);
        }
        NightbreakContentManager.getVersionCache().clear();
        NightbreakAccount.resetForTests();
    }

    @Test
    void pluginDownloadRepeatsDailyAndFetchesEachReleaseOnce() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            RecordingScheduler scheduler = stubBukkit(bukkit, null);

            NightbreakPluginUpdater.scheduleAutomaticPluginDownloads(plugin, SPEC);
            Repeating daily = scheduler.onlyRepeating();
            assertEquals(ONE_DAY_TICKS, daily.period(), "the automatic plugin download must repeat every 24 hours");

            // Day 1: 2.4.0 is published and the server runs 2.3.0.
            scheduler.runDay(daily);
            assertEquals(1, pluginDownloadRequests.get());
            assertEquals("2.4.0", NightbreakPluginHotSwap.descriptorVersion(updateJar().toFile()));

            // Days 2 and 3: the same release is already waiting for a restart.
            scheduler.runDay(daily);
            scheduler.runDay(daily);
            assertEquals(1, pluginDownloadRequests.get(), "a release already in the update folder must not be fetched again");

            // Day 4: a newer release replaces the waiting one.
            pluginArtifact = pluginJar("2.5.0");
            pluginVersion = "2.5.0";
            scheduler.runDay(daily);
            assertEquals(2, pluginDownloadRequests.get());
            assertEquals("2.5.0", NightbreakPluginHotSwap.descriptorVersion(updateJar().toFile()));
        }
    }

    @Test
    void pluginDownloadReadsTheSettingOnEveryPass() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            RecordingScheduler scheduler = stubBukkit(bukkit, null);
            setAutomaticUpdates(false);

            NightbreakPluginUpdater.scheduleAutomaticPluginDownloads(plugin, SPEC);
            Repeating daily = scheduler.onlyRepeating();

            scheduler.runDay(daily);
            assertEquals(0, pluginVersionRequests.get(), "a disabled setting must not contact the update service");
            assertFalse(Files.exists(updateJar()));

            setAutomaticUpdates(true);
            scheduler.runDay(daily);
            assertEquals(1, pluginDownloadRequests.get(), "switching the setting on must take effect on the next pass");

            setAutomaticUpdates(false);
            pluginArtifact = pluginJar("2.5.0");
            pluginVersion = "2.5.0";
            scheduler.runDay(daily);
            assertEquals(1, pluginDownloadRequests.get(), "switching the setting off must stop the following passes");
        }
    }

    @Test
    void manualDownloadStillReplacesAWaitingRelease() throws Exception {
        NightbreakPluginUpdater.PluginUpdateDownload first =
                NightbreakPluginUpdater.downloadPluginUpdate(plugin, SPEC, null);
        NightbreakPluginUpdater.PluginUpdateDownload second =
                NightbreakPluginUpdater.downloadPluginUpdate(plugin, SPEC, null);

        assertEquals(NightbreakPluginUpdater.DownloadStatus.DOWNLOADED, first.status());
        assertEquals(NightbreakPluginUpdater.DownloadStatus.DOWNLOADED, second.status());
        assertEquals(2, pluginDownloadRequests.get());
    }

    @Test
    void contentDownloadRepeatsDailyAndFetchesEachVersionOnce() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            RecordingScheduler scheduler = stubBukkit(bukkit, mock(ConsoleCommandSender.class));
            TestContent pack = new TestContent("pack", 1);
            AtomicBoolean guard = new AtomicBoolean(false);
            AtomicInteger reloads = new AtomicInteger();

            NightbreakPluginUpdater.autoDownloadContentUpdatesIfEnabled(
                    plugin, SPEC, () -> List.of(pack), guard, sender -> reloads.incrementAndGet());
            Repeating daily = scheduler.onlyRepeating();
            assertEquals(ONE_DAY_TICKS, daily.period(), "the automatic content download must repeat every 24 hours");

            // Day 1: catalog version 2 is newer than the installed version 1.
            scheduler.runDay(daily);
            assertEquals(1, contentDownloadRequests.get());
            assertTrue(Files.exists(importsFolder().resolve("pack.zip")));
            assertFalse(guard.get(), "the pass must release the bulk-operation guard");

            // Days 2 and 3: version 2 is already waiting in imports/ for a restart.
            scheduler.runDay(daily);
            scheduler.runDay(daily);
            assertEquals(1, contentDownloadRequests.get(), "a content update already in imports/ must not be fetched again");
            assertFalse(guard.get());

            // Day 4: catalog version 3 replaces the waiting download.
            contentVersion = 3;
            scheduler.runDay(daily);
            assertEquals(2, contentDownloadRequests.get());

            assertEquals(0, reloads.get(), "automatic content downloads wait for a restart");
        }
    }

    @Test
    void contentDownloadReadsTheSettingOnEveryPass() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            RecordingScheduler scheduler = stubBukkit(bukkit, mock(ConsoleCommandSender.class));
            setAutomaticUpdates(false);
            TestContent pack = new TestContent("pack", 1);

            NightbreakPluginUpdater.autoDownloadContentUpdatesIfEnabled(
                    plugin, SPEC, () -> List.of(pack), new AtomicBoolean(false), sender -> {
                    });
            Repeating daily = scheduler.onlyRepeating();

            scheduler.runDay(daily);
            assertEquals(0, contentDownloadRequests.get());

            setAutomaticUpdates(true);
            scheduler.runDay(daily);
            assertEquals(1, contentDownloadRequests.get(), "switching the setting on must take effect on the next pass");
        }
    }

    @Test
    void aReloadForgetsWhichContentUpdatesWereWaiting() throws Exception {
        try (MockedStatic<Bukkit> bukkit = mockStatic(Bukkit.class)) {
            RecordingScheduler scheduler = stubBukkit(bukkit, mock(ConsoleCommandSender.class));
            TestContent pack = new TestContent("pack", 1);
            AtomicBoolean guard = new AtomicBoolean(false);

            NightbreakPluginUpdater.autoDownloadContentUpdatesIfEnabled(
                    plugin, SPEC, () -> List.of(pack), guard, sender -> {
                    });
            scheduler.runDay(scheduler.onlyRepeating());
            assertEquals(1, contentDownloadRequests.get());

            // A reload imports what was waiting and starts a new plugin lifecycle. If the import
            // did not bring the package up to date, the next pass has to fetch it again.
            NightbreakPluginUpdater.shutdown(plugin);
            NightbreakBulkDownloader.shutdown(plugin);
            scheduler.forgetRepeating();
            NightbreakPluginUpdater.autoDownloadContentUpdatesIfEnabled(
                    plugin, SPEC, () -> List.of(pack), guard, sender -> {
                    });
            scheduler.runDay(scheduler.onlyRepeating());
            assertEquals(2, contentDownloadRequests.get());
        }
    }

    private record Repeating(Runnable task, long delay, long period) {
    }

    /** Records repeating tasks and queues one-off tasks until the test drains them. */
    private static final class RecordingScheduler {
        private final List<Repeating> repeating = new ArrayList<>();
        private final Deque<Runnable> queued = new ArrayDeque<>();
        private final AtomicInteger taskIds = new AtomicInteger(100);
        private final BukkitScheduler scheduler = mock(BukkitScheduler.class, invocation -> {
            Object[] arguments = invocation.getArguments();
            switch (invocation.getMethod().getName()) {
                case "scheduleSyncRepeatingTask" -> {
                    repeating.add(new Repeating((Runnable) arguments[1], (Long) arguments[2], (Long) arguments[3]));
                    return taskIds.incrementAndGet();
                }
                case "runTask", "runTaskAsynchronously", "runTaskLater" -> {
                    queued.add((Runnable) arguments[1]);
                    BukkitTask task = mock(BukkitTask.class);
                    return task;
                }
                case "isQueued", "isCurrentlyRunning" -> {
                    return false;
                }
                case "cancelTask" -> {
                    return null;
                }
                default -> throw new UnsupportedOperationException(
                        "BukkitScheduler." + invocation.getMethod().getName() + " is not stubbed for this test");
            }
        });

        private Repeating onlyRepeating() {
            assertEquals(1, repeating.size(), "exactly one repeating task was expected");
            return repeating.get(0);
        }

        private void forgetRepeating() {
            repeating.clear();
        }

        private void runDay(Repeating daily) {
            daily.task().run();
            int steps = 0;
            while (!queued.isEmpty()) {
                assertTrue(++steps < 1000, "the pass never settled");
                queued.poll().run();
            }
        }
    }

    private RecordingScheduler stubBukkit(MockedStatic<Bukkit> bukkit, ConsoleCommandSender console) {
        RecordingScheduler scheduler = new RecordingScheduler();
        Logger logger = Logger.getLogger("NightbreakAutoUpdateRepeatTest");
        bukkit.when(Bukkit::getScheduler).thenReturn(scheduler.scheduler);
        bukkit.when(Bukkit::getLogger).thenReturn(logger);
        bukkit.when(Bukkit::getOnlinePlayers).thenReturn(List.of());
        bukkit.when(Bukkit::getConsoleSender).thenReturn(console);
        return scheduler;
    }

    private void setAutomaticUpdates(boolean enabled) throws IOException {
        Files.writeString(plugin.getDataFolder().toPath().resolve("config.yml"),
                "nightbreak:\n  autoDownloadPluginUpdates: " + enabled + "\n", StandardCharsets.UTF_8);
    }

    private Path updateJar() {
        return plugin.getDataFolder().toPath().getParent().resolve("update").resolve("FreeMinecraftModels.jar");
    }

    private Path importsFolder() {
        return plugin.getDataFolder().toPath().resolve("imports");
    }

    private void startFixture() throws IOException {
        httpServer = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        httpServer.createContext("/server/plugins/freeminecraftmodels/version", exchange -> {
            pluginVersionRequests.incrementAndGet();
            byte[] artifact = pluginArtifact;
            respond(exchange, "application/json", ("{\"slug\":\"freeminecraftmodels\",\"version\":\"" + pluginVersion
                    + "\",\"fileName\":\"FreeMinecraftModels.jar\",\"checksum\":\"" + sha256(artifact) + "\"}")
                    .getBytes(StandardCharsets.UTF_8));
        });
        httpServer.createContext("/server/plugins/freeminecraftmodels/download", exchange -> {
            pluginDownloadRequests.incrementAndGet();
            respond(exchange, "application/java-archive", pluginArtifact);
        });
        httpServer.createContext("/api/dlc/versions", exchange -> respond(exchange, "application/json",
                ("{\"versions\":[{\"slug\":\"pack\",\"version\":\"" + contentVersion + "\",\"versionInt\":"
                        + contentVersion + "}]}").getBytes(StandardCharsets.UTF_8)));
        httpServer.createContext("/server/dlc/pack/access", exchange -> respond(exchange, "application/json",
                "{\"slug\":\"pack\",\"hasAccess\":true}".getBytes(StandardCharsets.UTF_8)));
        httpServer.createContext("/server/dlc/pack/download", exchange -> {
            contentDownloadRequests.incrementAndGet();
            respond(exchange, "application/zip", ("pack version " + contentVersion).getBytes(StandardCharsets.UTF_8));
        });
        httpServer.start();
        baseUrlScope = NightbreakAccount.useLoopbackBaseUrlForTests(
                URI.create("http://127.0.0.1:" + httpServer.getAddress().getPort()));
    }

    private static void respond(HttpExchange exchange, String contentType, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", contentType);
        exchange.sendResponseHeaders(200, body.length);
        try (exchange; var output = exchange.getResponseBody()) {
            output.write(body);
        }
    }

    private static byte[] pluginJar(String version) throws IOException {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        try (JarOutputStream jar = new JarOutputStream(bytes)) {
            jar.putNextEntry(new JarEntry("plugin.yml"));
            jar.write(("name: FreeMinecraftModels\nversion: " + version + "\nmain: " + FIXTURE_MAIN + "\n")
                    .getBytes(StandardCharsets.UTF_8));
            jar.closeEntry();
            String classEntry = FIXTURE_MAIN.replace('.', '/') + ".class";
            jar.putNextEntry(new JarEntry(classEntry));
            try (var input = NightbreakAutoUpdateRepeatTest.class.getResourceAsStream("/" + classEntry)) {
                if (input == null) throw new IOException("Missing fixture class resource " + classEntry);
                input.transferTo(jar);
            }
            jar.closeEntry();
        }
        return bytes.toByteArray();
    }

    private static String sha256(byte[] bytes) {
        try {
            StringBuilder value = new StringBuilder();
            for (byte element : MessageDigest.getInstance("SHA-256").digest(bytes)) {
                value.append(String.format("%02x", element));
            }
            return value.toString();
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException(exception);
        }
    }

    /** An installed package that stays at its local version until a restart imports the download. */
    private static final class TestContent implements NightbreakManagedContent {
        private final String slug;
        private final int localVersion;
        private boolean outOfDate;
        private NightbreakAccount.AccessInfo accessInfo;

        private TestContent(String slug, int localVersion) {
            this.slug = slug;
            this.localVersion = localVersion;
        }

        @Override
        public String getNightbreakSlug() {
            return slug;
        }

        @Override
        public String getDisplayName() {
            return slug;
        }

        @Override
        public String getDownloadLink() {
            return "";
        }

        @Override
        public int getLocalVersion() {
            return localVersion;
        }

        @Override
        public boolean isInstalled() {
            return true;
        }

        @Override
        public boolean isDownloaded() {
            return true;
        }

        @Override
        public boolean isOutOfDate() {
            return outOfDate;
        }

        @Override
        public void setOutOfDate(boolean outOfDate) {
            this.outOfDate = outOfDate;
        }

        @Override
        public NightbreakAccount.AccessInfo getCachedAccessInfo() {
            return accessInfo;
        }

        @Override
        public void setCachedAccessInfo(NightbreakAccount.AccessInfo accessInfo) {
            this.accessInfo = accessInfo;
        }
    }
}
