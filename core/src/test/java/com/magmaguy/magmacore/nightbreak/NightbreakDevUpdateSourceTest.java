package com.magmaguy.magmacore.nightbreak;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/** The dev source swaps a build once, only after the file stops changing, and never the running or skipped bytes. */
class NightbreakDevUpdateSourceTest {

    @TempDir
    Path folder;

    private File write(String name, String content, long modified) throws Exception {
        File file = folder.resolve(name).toFile();
        Files.writeString(file.toPath(), content);
        file.setLastModified(modified);
        return file;
    }

    @Test
    void missingSourceDecidesNothing() {
        var watch = new NightbreakDevUpdateSource.BuildWatch(folder.resolve("EliteMobs.jar").toFile());
        assertNull(watch.poll("running", null));
        assertNull(watch.poll("running", null));
    }

    @Test
    void newBuildIsReportedOnceAfterItStopsChanging() throws Exception {
        File jar = write("EliteMobs.jar", "build one", 1_000_000L);
        var watch = new NightbreakDevUpdateSource.BuildWatch(jar);
        assertNull(watch.poll("running", null), "first sight waits: a build may still be writing");
        String hash = NightbreakPluginUpdater.sha256(jar);
        assertEquals(hash, watch.poll("running", null));
        assertNull(watch.poll("running", null), "a decided build is not reported again");
    }

    @Test
    void changingFileWaitsUntilStable() throws Exception {
        File jar = write("EliteMobs.jar", "partial", 1_000_000L);
        var watch = new NightbreakDevUpdateSource.BuildWatch(jar);
        assertNull(watch.poll("running", null));
        write("EliteMobs.jar", "partial plus more", 1_002_000L);
        assertNull(watch.poll("running", null), "the size and time changed, so it is still being written");
        assertEquals(NightbreakPluginUpdater.sha256(jar), watch.poll("running", null));
    }

    @Test
    void runningAndSkippedBytesAreNeverReported() throws Exception {
        File jar = write("EliteMobs.jar", "same bytes", 1_000_000L);
        String hash = NightbreakPluginUpdater.sha256(jar);
        var running = new NightbreakDevUpdateSource.BuildWatch(jar);
        running.poll(hash, null);
        assertNull(running.poll(hash, null), "the build already runs");
        var skipped = new NightbreakDevUpdateSource.BuildWatch(jar);
        skipped.poll("running", hash);
        assertNull(skipped.poll("running", hash), "this build was refused or failed before");
    }

    @Test
    void retryDecidesTheSameBuildAgain() throws Exception {
        File jar = write("EliteMobs.jar", "build", 1_000_000L);
        var watch = new NightbreakDevUpdateSource.BuildWatch(jar);
        watch.poll("running", null);
        String hash = watch.poll("running", null);
        watch.retry();
        assertEquals(hash, watch.poll("running", null), "a busy swap tries the same build on the next poll");
    }

    @Test
    void nextBuildAfterADecisionIsReported() throws Exception {
        File jar = write("EliteMobs.jar", "build one", 1_000_000L);
        var watch = new NightbreakDevUpdateSource.BuildWatch(jar);
        watch.poll("running", null);
        String first = watch.poll("running", null);
        write("EliteMobs.jar", "build two!", 1_004_000L);
        assertNull(watch.poll("running", first));
        assertEquals(NightbreakPluginUpdater.sha256(jar), watch.poll("running", first));
    }
}
