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
 * EternalTD now converts its level templates in place (MagmaCore e5f6039), so an import into worlds/ must replace
 * each template whole instead of merging an older export into a converted one. Other folders still merge.
 */
@EnabledIfEnvironmentVariable(named = "NIGHTBREAK_TEMP_TICKET_TESTS", matches = "true")
class ConfigurationImporterWorldsTemporaryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void importReplacesAConvertedTemplateWholeAndLeavesSiblingsAlone() throws Exception {
        Path installed = temporaryDirectory.resolve("plugins/EternalTD/worlds");
        write(installed.resolve("etd_map/region/r.0.0.mca"), "converted terrain");
        write(installed.resolve("etd_map/data/paper/level_overrides.dat"), "converted settings");
        write(installed.resolve("etd_other/region/r.0.0.mca"), "other template");
        Path archive = temporaryDirectory.resolve("archive/worlds");
        write(archive.resolve("etd_map/level.dat"), "legacy level data");
        write(archive.resolve("etd_map/DIM-1/region/r.0.0.mca"), "updated terrain");

        ConfigurationImporter.importContentFolder(archive, installed, temporaryDirectory.resolve("imports"));

        assertEquals(List.of("DIM-1/region/r.0.0.mca", "level.dat"), files(installed.resolve("etd_map")));
        assertEquals("updated terrain", Files.readString(installed.resolve("etd_map/DIM-1/region/r.0.0.mca")));
        assertEquals("other template", Files.readString(installed.resolve("etd_other/region/r.0.0.mca")));
        assertEquals(List.of(), files(temporaryDirectory.resolve("imports")), "the committed transaction leaves no backup");
    }

    @Test
    void failedImportRestoresTheReplacedTemplate() throws Exception {
        Path installed = temporaryDirectory.resolve("plugins/EternalTD/worlds");
        write(installed.resolve("a_map/region/r.0.0.mca"), "converted terrain");
        write(installed.resolve("a_map/data/paper/level_overrides.dat"), "converted settings");
        // A regular file where the archive's second template goes makes the import fail after the first replacement.
        write(installed.resolve("b_map"), "not a directory");
        Path archive = temporaryDirectory.resolve("archive/worlds");
        write(archive.resolve("a_map/level.dat"), "legacy level data");
        write(archive.resolve("b_map/level.dat"), "legacy level data");

        assertThrows(IOException.class, () ->
                ConfigurationImporter.importContentFolder(archive, installed, temporaryDirectory.resolve("imports")));

        assertEquals(List.of("data/paper/level_overrides.dat", "region/r.0.0.mca"), files(installed.resolve("a_map")));
        assertEquals("converted terrain", Files.readString(installed.resolve("a_map/region/r.0.0.mca")));
        assertEquals("not a directory", Files.readString(installed.resolve("b_map")));
    }

    @Test
    void otherFoldersStillMerge() throws Exception {
        Path installed = temporaryDirectory.resolve("plugins/EternalTD/levels");
        write(installed.resolve("grassy_lands/admin_level.yml"), "admin level");
        write(installed.resolve("grassy_lands/tabula_rasa.yml"), "old level");
        Path archive = temporaryDirectory.resolve("archive/levels");
        write(archive.resolve("grassy_lands/tabula_rasa.yml"), "new level");

        ConfigurationImporter.importContentFolder(archive, installed, temporaryDirectory.resolve("imports"));

        assertEquals(List.of("admin_level.yml", "tabula_rasa.yml"), files(installed.resolve("grassy_lands")));
        assertEquals("new level", Files.readString(installed.resolve("grassy_lands/tabula_rasa.yml")));
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
