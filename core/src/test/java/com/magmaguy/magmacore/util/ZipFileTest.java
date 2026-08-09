package com.magmaguy.magmacore.util;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Enumeration;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ZipFileTest {
    @TempDir
    Path temporaryDirectory;

    @Test
    void directoryRoundTripPreservesContentAndDeterministicEntryTimes() throws Exception {
        Path source = Files.createDirectory(temporaryDirectory.resolve("source"));
        Path nested = Files.createDirectory(source.resolve("nested"));
        Files.writeString(source.resolve("root.txt"), "root");
        Files.writeString(nested.resolve("child.txt"), "child");
        Path archive = temporaryDirectory.resolve("pack.zip");

        ZipFile.ZipUtility.zip(source.toFile(), archive.toString());

        try (java.util.zip.ZipFile zip = new java.util.zip.ZipFile(archive.toFile())) {
            Enumeration<? extends ZipEntry> entries = zip.entries();
            int count = 0;
            while (entries.hasMoreElements()) {
                assertEquals(0L, entries.nextElement().getTime());
                count++;
            }
            assertEquals(2, count);
        }

        Path destination = Files.createDirectory(temporaryDirectory.resolve("unzipped"));
        ZipFile.unzip(archive.toFile(), destination.toFile());
        assertEquals("root", Files.readString(destination.resolve("root.txt")));
        assertEquals("child", Files.readString(destination.resolve("nested/child.txt")));
        assertTrue(Files.deleteIfExists(archive));
    }

    @Test
    void rejectsTraversalAndLeavesNoPartialExtraction() throws Exception {
        Path archive = temporaryDirectory.resolve("traversal.zip");
        try (ZipOutputStream output =
                     new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("valid.txt"));
            output.write("valid".getBytes());
            output.closeEntry();
            output.putNextEntry(new ZipEntry("../escape.txt"));
            output.write("escape".getBytes());
            output.closeEntry();
        }
        Path destination =
                Files.createDirectory(temporaryDirectory.resolve("target"));

        assertThrows(
                java.io.IOException.class,
                () -> ZipFile.unzip(archive.toFile(), destination.toFile()));

        assertFalse(Files.exists(destination.resolve("valid.txt")));
        assertFalse(Files.exists(temporaryDirectory.resolve("escape.txt")));
    }

    @Test
    void rejectsCaseInsensitiveDuplicatePaths() throws Exception {
        Path archive = temporaryDirectory.resolve("duplicates.zip");
        try (ZipOutputStream output =
                     new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("assets/model.json"));
            output.write("first".getBytes());
            output.closeEntry();
            output.putNextEntry(new ZipEntry("ASSETS/MODEL.JSON"));
            output.write("second".getBytes());
            output.closeEntry();
        }
        Path destination =
                Files.createDirectory(temporaryDirectory.resolve("target"));

        java.io.IOException failure = assertThrows(
                java.io.IOException.class,
                () -> ZipFile.unzip(archive.toFile(), destination.toFile()));
        assertTrue(failure.getMessage().toLowerCase(Locale.ROOT)
                .contains("duplicate"));
        try (var children = Files.list(destination)) {
            assertEquals(0L, children.count());
        }
    }

    @Test
    void enforcesEntryAndExpandedByteLimitsBeforeLeavingOutput()
            throws Exception {
        Path archive = temporaryDirectory.resolve("limited.zip");
        try (ZipOutputStream output =
                     new ZipOutputStream(Files.newOutputStream(archive))) {
            output.putNextEntry(new ZipEntry("large.txt"));
            output.write("1234567890".getBytes());
            output.closeEntry();
        }
        Path destination =
                Files.createDirectory(temporaryDirectory.resolve("target"));
        ZipFile.ExtractionLimits limits = new ZipFile.ExtractionLimits(
                10, 1024, 5, 5, 1000.0);

        assertThrows(
                java.io.IOException.class,
                () -> ZipFile.unzip(
                        archive.toFile(),
                        destination.toFile(),
                        limits));
        try (var children = Files.list(destination)) {
            assertEquals(0L, children.count());
        }
    }
}
