package com.magmaguy.magmacore.dlc;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Dungeon load times ticket (2026-10-25): EliteMobs converts installed blueprints in place, so an
 * import must replace a blueprint whole instead of merging an old-format export into a converted one.
 */
@EnabledIfEnvironmentVariable(named = "NIGHTBREAK_TEMP_TICKET_TESTS", matches = "true")
class ConfigurationImporterWorldBlueprintTemporaryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void importReplacesAConvertedBlueprintWholeAndLeavesSiblingsAlone() throws Exception {
        Path installed = temporaryDirectory.resolve("plugins/EliteMobs/world_blueprints");
        write(installed.resolve("pack/dungeon/region/r.0.0.mca"), "converted terrain");
        write(installed.resolve("pack/dungeon/data/paper/level_overrides.dat"), "converted settings");
        write(installed.resolve("pack/dungeon/poi/r.0.0.mca"), "converted poi");
        write(installed.resolve("pack/other/region/r.0.0.mca"), "other blueprint");
        Path archive = temporaryDirectory.resolve("archive/world_blueprints");
        write(archive.resolve("pack/dungeon/level.dat"), "legacy level data");
        write(archive.resolve("pack/dungeon/region/r.0.0.mca"), "updated terrain");

        ConfigurationImporter.importWorldBlueprints(archive, installed, temporaryDirectory.resolve("imports"));

        assertEquals(List.of("level.dat", "region/r.0.0.mca"), files(installed.resolve("pack/dungeon")));
        assertEquals("updated terrain", Files.readString(installed.resolve("pack/dungeon/region/r.0.0.mca")));
        assertEquals("other blueprint", Files.readString(installed.resolve("pack/other/region/r.0.0.mca")));
        assertEquals(List.of(), files(temporaryDirectory.resolve("imports")), "the committed transaction leaves no backup");
    }

    @Test
    void failedImportRestoresTheReplacedBlueprint() throws Exception {
        Path installed = temporaryDirectory.resolve("plugins/EliteMobs/world_blueprints");
        write(installed.resolve("pack/a_dungeon/region/r.0.0.mca"), "converted terrain");
        write(installed.resolve("pack/a_dungeon/data/paper/level_overrides.dat"), "converted settings");
        // A regular file where the archive's second blueprint goes makes the import fail after the first replacement.
        write(installed.resolve("pack/b_dungeon"), "not a directory");
        Path archive = temporaryDirectory.resolve("archive/world_blueprints");
        write(archive.resolve("pack/a_dungeon/level.dat"), "legacy level data");
        write(archive.resolve("pack/b_dungeon/level.dat"), "legacy level data");

        assertThrows(IOException.class, () ->
                ConfigurationImporter.importWorldBlueprints(archive, installed, temporaryDirectory.resolve("imports")));

        assertEquals(List.of("data/paper/level_overrides.dat", "region/r.0.0.mca"), files(installed.resolve("pack/a_dungeon")));
        assertEquals("converted terrain", Files.readString(installed.resolve("pack/a_dungeon/region/r.0.0.mca")));
        assertEquals("not a directory", Files.readString(installed.resolve("pack/b_dungeon")));
    }

    private static void write(Path file, String content) throws IOException {
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private static List<String> files(Path root) throws IOException {
        if (!Files.exists(root)) return List.of();
        try (Stream<Path> paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile)
                    .map(path -> root.relativize(path).toString().replace('\\', '/'))
                    .sorted()
                    .toList();
        }
    }
}
