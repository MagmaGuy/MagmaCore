package com.magmaguy.magmacore;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

class MagmaCoreSharedAssetsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void checksumDetectsSameSizeContentChanges() throws Exception {
        Path source = Files.createDirectories(
                temporaryDirectory.resolve("source"));
        Path asset = source.resolve("asset.json");
        Files.writeString(asset, "AA");
        String first = MagmaCore.calculateDirectoryAssetChecksum(source);

        Files.writeString(asset, "BB");
        String second = MagmaCore.calculateDirectoryAssetChecksum(source);

        assertNotEquals(first, second);
    }

    @Test
    void installReplacesOnlyTheManagedTreeAndRemovesDeletedAssets()
            throws Exception {
        Path source = Files.createDirectories(
                temporaryDirectory.resolve("source"));
        Files.writeString(source.resolve("current.json"), "new");

        Path resourcePack = Files.createDirectories(
                temporaryDirectory.resolve("resource_pack"));
        Path managed = Files.createDirectories(
                resourcePack.resolve("nightbreak_rsp_defaults"));
        Files.writeString(managed.resolve("current.json"), "old");
        Files.writeString(managed.resolve("deleted.json"), "stale");
        Files.writeString(resourcePack.resolve("consumer-owned.json"), "keep");

        MagmaCore.installSharedAssetDirectory(source, resourcePack);

        assertEquals("new", Files.readString(
                managed.resolve("current.json")));
        assertFalse(Files.exists(managed.resolve("deleted.json")));
        assertEquals("keep", Files.readString(
                resourcePack.resolve("consumer-owned.json")));
    }
}
