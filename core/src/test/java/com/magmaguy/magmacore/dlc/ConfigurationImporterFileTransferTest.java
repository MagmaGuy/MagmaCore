package com.magmaguy.magmacore.dlc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigurationImporterFileTransferTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void mergesDirectoryContentsAndAtomicallyReplacesCollidingFiles() throws Exception {
        Path source = temporaryDirectory.resolve("source/em_id_frost_palace_rsp");
        Path destination = temporaryDirectory.resolve("target/em_id_frost_palace_rsp");
        Files.createDirectories(source.resolve("assets/minecraft"));
        Files.createDirectories(destination.resolve("assets/minecraft"));
        Files.writeString(source.resolve("assets/minecraft/sounds.json"), "new", StandardCharsets.UTF_8);
        Files.writeString(destination.resolve("assets/minecraft/sounds.json"), "old", StandardCharsets.UTF_8);
        Files.writeString(destination.resolve("existing.txt"), "keep", StandardCharsets.UTF_8);

        ConfigurationImporter.mergeImportEntry(source, destination);

        assertEquals("new", Files.readString(destination.resolve("assets/minecraft/sounds.json")));
        assertEquals("keep", Files.readString(destination.resolve("existing.txt")));
        assertTrue(Files.exists(source.resolve("assets/minecraft/sounds.json")),
                "the extracted source must remain until the complete package succeeds");
    }

    @Test
    void concurrentDirectoryCreationDoesNotDropEitherImportTree() throws Exception {
        Path firstSource = temporaryDirectory.resolve("first/resource_pack");
        Path secondSource = temporaryDirectory.resolve("second/resource_pack");
        Path destination = temporaryDirectory.resolve("target/resource_pack");
        Files.createDirectories(firstSource.resolve("first-pack"));
        Files.createDirectories(secondSource.resolve("second-pack"));
        Files.writeString(firstSource.resolve("first-pack/first.txt"), "first");
        Files.writeString(secondSource.resolve("second-pack/second.txt"), "second");

        CountDownLatch start = new CountDownLatch(1);
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            Future<?> first = executor.submit(() -> {
                start.await();
                ConfigurationImporter.mergeImportEntry(firstSource, destination);
                return null;
            });
            Future<?> second = executor.submit(() -> {
                start.await();
                ConfigurationImporter.mergeImportEntry(secondSource, destination);
                return null;
            });
            start.countDown();
            first.get();
            second.get();
        }

        assertEquals("first", Files.readString(destination.resolve("first-pack/first.txt")));
        assertEquals("second", Files.readString(destination.resolve("second-pack/second.txt")));
    }

    @Test
    void archiveTransactionRestoresEveryEarlierMutationWhenALaterEntryFails()
            throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path destination = temporaryDirectory.resolve("target");
        Path transactionWorkspace = temporaryDirectory.resolve("transactions");
        Files.createDirectories(source);
        Files.createDirectories(destination);
        Files.writeString(source.resolve("replacement.txt"), "new");
        Files.writeString(source.resolve("created.txt"), "created");
        Files.writeString(destination.resolve("replacement.txt"), "old");
        Path missingSource = source.resolve("missing.txt");

        assertThrows(
                java.io.IOException.class,
                () -> ConfigurationImporter.mergeImportEntriesAtomically(
                        java.util.List.of(
                                new ConfigurationImporter.ImportTransfer(
                                        source.resolve("replacement.txt"),
                                        destination.resolve("replacement.txt")),
                                new ConfigurationImporter.ImportTransfer(
                                        source.resolve("created.txt"),
                                        destination.resolve("created.txt")),
                                new ConfigurationImporter.ImportTransfer(
                                        missingSource,
                                        destination.resolve("never.txt"))),
                        transactionWorkspace));

        assertEquals(
                "old",
                Files.readString(destination.resolve("replacement.txt")),
                "an overwritten destination must be restored exactly");
        assertFalse(
                Files.exists(destination.resolve("created.txt")),
                "a file created before the failure must be removed");
        assertFalse(Files.exists(destination.resolve("never.txt")));
        assertEquals(
                "new",
                Files.readString(source.resolve("replacement.txt")),
                "rollback must not consume extracted source content");
        try (var leftovers = Files.list(transactionWorkspace)) {
            assertEquals(
                    0L,
                    leftovers.count(),
                    "completed rollback must not leave transaction debris");
        }
    }
}
