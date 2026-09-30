package com.magmaguy.magmacore.match;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/** Uses the MockBukkit server only because MagmaCore's Logger writes through Bukkit. */
class LeftoverWorldsTest extends MatchTestSupport {
    @TempDir
    Path container;

    @Test
    void sweepDeletesOnlyUnloadedFoldersMarkedByThisPlugin() throws IOException {
        LeftoverWorlds.writeMarker(Files.createDirectories(container.resolve("mine")), "MatchTest", "mine");
        LeftoverWorlds.writeMarker(Files.createDirectories(container.resolve("loaded")), "MatchTest", "loaded");
        LeftoverWorlds.writeMarker(Files.createDirectories(container.resolve("theirs")), "OtherPlugin", "theirs");
        Files.createDirectories(container.resolve("unmarked"));

        List<String> deleted = new ArrayList<>();
        LeftoverWorlds.sweep("MatchTest", List.of("mine", "loaded", "theirs", "unmarked"),
                container::resolve, "loaded"::equals, deleted::add);

        assertEquals(List.of("mine"), deleted);
    }

    @Test
    void markerNamesTheOwner() throws IOException {
        Path folder = Files.createDirectories(container.resolve("w"));
        LeftoverWorlds.writeMarker(folder, "MatchTest", "w");
        assertEquals(List.of("magmacore-match-world-v1", "MatchTest", "w"),
                Files.readAllLines(folder.resolve(LeftoverWorlds.MARKER_FILE)));
    }

    @Test
    void aMarkerFromAnotherFormatIsLeftAlone() throws IOException {
        Path folder = Files.createDirectories(container.resolve("odd"));
        Files.writeString(folder.resolve(LeftoverWorlds.MARKER_FILE), "something-else\nMatchTest\nodd\n");
        List<String> deleted = new ArrayList<>();
        LeftoverWorlds.sweep("MatchTest", List.of("odd"), container::resolve, name -> false, deleted::add);
        assertTrue(deleted.isEmpty());
    }
}
