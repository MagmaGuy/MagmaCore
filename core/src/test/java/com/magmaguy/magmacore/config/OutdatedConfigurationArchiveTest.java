package com.magmaguy.magmacore.config;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class OutdatedConfigurationArchiveTest {
    private static final String SCRAP_RULE = """
            customitems:
              - files: [elite_scrap_tiny.yml, elite_scrap_small.yml]
                key: enchantments
                listEntryNames: [repair]
            """;
    private static final String QUEST_RULE = """
            customquests:
              - files: [ag_welcome_quest_1.yml]
                key: customObjectives.Objective13.filename
                value: scroll_applier_config.yml
            """;
    @TempDir Path temporary;
    private Path data() { return temporary.resolve("plugins/ExamplePlugin"); }
    private Path storage() { return temporary.resolve("plugins/MagmaCore/outdated files"); }
    private Map<String, Set<OutdatedConfigurationArchive.Rule>> rules() {
        return Map.of("customitems", Set.of(new OutdatedConfigurationArchive.KeyRule("enchantmentsV2")));
    }
    private Path write(String path, String yaml) throws IOException {
        Path file = data().resolve(path);
        Files.createDirectories(file.getParent());
        return Files.writeString(file, yaml, StandardCharsets.UTF_8);
    }

    @Test void preservesCustomizedBytesAndFullRelativePath() throws Exception {
        String contents = "# User customization\r\nname: My sword\r\nenchantmentsV2: [LOUD_STRIKES, 99]\r\n";
        Path original = write("customitems/oasis/room/item.yml", contents);
        List<Path> archived = OutdatedConfigurationArchive.archive(data(), storage(), rules());
        assertEquals(1, archived.size());
        assertFalse(Files.exists(original));
        assertTrue(archived.getFirst().endsWith(Path.of("customitems/oasis/room/item.yml")));
        assertEquals(contents, Files.readString(archived.getFirst()));
        assertEquals("ExamplePlugin", storage().relativize(archived.getFirst()).getName(0).toString());
    }

    @Test void checksExactCategoryAndKeysRatherThanCommentsOrValues() throws Exception {
        Path comment = write("customitems/comment.yml", "# enchantmentsV2: []\nname: enchantmentsV2\n");
        Path nested = write("customitems/nested.yaml", "other:\n  enchantmentsV2: []\n");
        Path otherCategory = write("custombosses/keep.yml", "enchantmentsV2: []\n");
        assertTrue(OutdatedConfigurationArchive.archive(data(), storage(), rules()).isEmpty());
        assertFalse(Files.exists(storage()));
        assertTrue(Files.exists(comment) && Files.exists(nested) && Files.exists(otherCategory));
    }

    @Test void archivesNullFalseAndEmptyRetiredValues() throws Exception {
        write("customitems/null.yml", "enchantmentsV2: null\n");
        write("customitems/false.yaml", "enchantmentsV2: false\n");
        write("customitems/empty.yml", "enchantmentsV2: []\n");
        assertEquals(3, OutdatedConfigurationArchive.archive(data(), storage(), rules()).size());
    }

    @Test void repeatedImportRetainsBothOriginalsAndUnchangedRunCreatesNothing() throws Exception {
        write("customitems/item.yml", "enchantmentsV2: [first]\n");
        Path first = OutdatedConfigurationArchive.archive(data(), storage(), rules()).getFirst();
        assertTrue(OutdatedConfigurationArchive.archive(data(), storage(), rules()).isEmpty());
        write("customitems/item.yml", "enchantmentsV2: [second]\n");
        Path second = OutdatedConfigurationArchive.archive(data(), storage(), rules()).getFirst();
        assertNotEquals(first, second);
        assertEquals("enchantmentsV2: [first]\n", Files.readString(first));
        assertEquals("enchantmentsV2: [second]\n", Files.readString(second));
        write("customitems/item.yml", "enchantmentsV3: [current]\n");
        assertTrue(OutdatedConfigurationArchive.archive(data(), storage(), rules()).isEmpty());
    }

    @Test void invalidYamlAbortsBeforeArchivingAnyFile() throws Exception {
        Path matching = write("customitems/a.yml", "enchantmentsV2: []\n");
        Path invalid = write("customitems/z.yml", "name: [unterminated\n");
        assertThrows(IOException.class, () -> OutdatedConfigurationArchive.archive(data(), storage(), rules()));
        assertTrue(Files.exists(matching) && Files.exists(invalid));
        assertFalse(Files.exists(storage()));
    }

    @Test void duplicateKeysCannotHideRetiredFields() throws Exception {
        Path original = write("customitems/a.yml", "enchantmentsV2: []\nenchantmentsV2: null\n");
        assertThrows(IOException.class, () -> OutdatedConfigurationArchive.archive(data(), storage(), rules()));
        assertTrue(Files.exists(original));
    }

    @Test void archiveStorageFailureLeavesOriginalIntact() throws Exception {
        Path original = write("customitems/item.yml", "enchantmentsV2: []\n");
        Files.createDirectories(storage().getParent());
        Files.writeString(storage(), "blocked");
        assertThrows(IOException.class, () -> OutdatedConfigurationArchive.archive(data(), storage(), rules()));
        assertTrue(Files.exists(original));
        assertEquals("blocked", Files.readString(storage()));
    }

    @Test void doesNotCreateArchiveWithNoMatchesOrMissingCategory() throws Exception {
        write("customitems/current.yml", "enchantmentsV3: []\n");
        assertTrue(OutdatedConfigurationArchive.archive(data(), storage(), rules()).isEmpty());
        assertTrue(OutdatedConfigurationArchive.archive(data(), storage(), Map.of("missing", Set.of(new OutdatedConfigurationArchive.KeyRule("old")))).isEmpty());
        assertFalse(Files.exists(storage()));
    }

    @Test void rejectsUnsafeRulesAndStorageInsideContent() throws Exception {
        assertThrows(IOException.class, () -> OutdatedConfigurationArchive.readRules("../outside: [old]".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> OutdatedConfigurationArchive.readRules("customitems: []".getBytes(StandardCharsets.UTF_8)));
        assertThrows(IOException.class, () -> OutdatedConfigurationArchive.archive(data(), data().resolve("archive"), rules()));
    }

    @Test void sharedSelectionAndConfigurationReaderRejectArchivedContent() throws Exception {
        Path source = write("customitems/item.yml", "enchantmentsV2: []\n");
        Path archived = OutdatedConfigurationArchive.archive(data(), storage(), rules()).getFirst();
        Path current = write("customitems/current.yml", "enchantmentsV3: []\n");
        assertEquals(List.of(current.toFile()), ContentFileSelector.select(List.of(archived.toFile(), current.toFile())));
        assertThrows(IllegalArgumentException.class, () -> ConfigurationEngine.fileConfigurationCreator(archived.toFile()));
        assertFalse(Files.exists(source));
    }

    @Test void parsesRulesWithoutPluginSpecificBehavior() throws Exception {
        assertEquals(Map.of("customitems", Set.of(new OutdatedConfigurationArchive.KeyRule("enchantmentsV2")),
                        "powers/nested", Set.of(new OutdatedConfigurationArchive.KeyRule("oldKey"), new OutdatedConfigurationArchive.KeyRule("olderKey"))),
                OutdatedConfigurationArchive.readRules("customitems: [enchantmentsV2]\npowers/nested: [oldKey, olderKey]\n".getBytes(StandardCharsets.UTF_8)));
    }

    @Test void pluginEntryPointReadsBundledRulesAndCanRunAroundAnImport() throws Exception {
        var plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        when(plugin.getDataFolder()).thenReturn(data().toFile());
        when(plugin.getLogger()).thenReturn(java.util.logging.Logger.getAnonymousLogger());
        when(plugin.getResource(OutdatedConfigurationArchive.RULES_RESOURCE)).thenAnswer(ignored ->
                new ByteArrayInputStream("customitems: [enchantmentsV2]\n".getBytes(StandardCharsets.UTF_8)));
        Path destination = write("customitems/package/item.yml", "# Customized\nenchantmentsV2: [old]\n");
        OutdatedConfigurationArchive.archive(plugin);
        assertFalse(Files.exists(destination));
        // Existing file has already been preserved when an import writes the new version.
        write("customitems/package/item.yml", "enchantmentsV3: [current]\n");
        OutdatedConfigurationArchive.archive(plugin);
        assertEquals("enchantmentsV3: [current]\n", Files.readString(destination));
        try (var files = Files.walk(storage())) {
            List<Path> originals = files.filter(Files::isRegularFile).toList();
            assertEquals(1, originals.size());
            assertEquals("# Customized\nenchantmentsV2: [old]\n", Files.readString(originals.getFirst()));
        }
    }

    @Test void pluginWithoutRulesDoesNotCreateAnything() {
        var plugin = mock(org.bukkit.plugin.java.JavaPlugin.class);
        OutdatedConfigurationArchive.archive(plugin);
        assertFalse(Files.exists(storage()));
        verify(plugin, never()).getDataFolder();
    }

    @Test void archivesOnlySelectedFilesWithRetiredListEntries() throws Exception {
        String original = "# customized\r\nname: My scrap\r\nenchantments: [RePaIr, 'REPAIR,1']\r\nconsumable: {type: repair_scrap, tier: 1}\r\n";
        Path retired = write("customitems/nested/elite_scrap_tiny.yml", original);
        Path current = write("customitems/elite_scrap_small.yml", "enchantments: ['other:repair,2']\n");
        Path unrelated = write("customitems/sword.yml", "enchantments: ['repair,1']\n");
        Path otherCategory = write("custombosses/elite_scrap_tiny.yml", "enchantments: ['repair,1']\n");
        var selected = OutdatedConfigurationArchive.readRules(SCRAP_RULE.getBytes(StandardCharsets.UTF_8));
        List<Path> archived = OutdatedConfigurationArchive.archive(data(), storage(), selected);
        assertEquals(1, archived.size());
        assertEquals(original, Files.readString(archived.getFirst()));
        assertFalse(Files.exists(retired));
        assertTrue(Files.exists(current) && Files.exists(unrelated) && Files.exists(otherCategory));
        write("customitems/nested/elite_scrap_tiny.yml", "consumable: {type: repair_scrap, tier: 1}\n");
        assertTrue(OutdatedConfigurationArchive.archive(data(), storage(), selected).isEmpty());
    }

    @Test void listEntryRulesIgnoreCommentsNestedKeysAndNonListValues() throws Exception {
        var selected = OutdatedConfigurationArchive.readRules(SCRAP_RULE.getBytes(StandardCharsets.UTF_8));
        for (String yaml : List.of("# enchantments: ['repair,1']\nname: repair\n",
                "other: {enchantments: ['repair,1']}\n", "enchantments: 'repair,1'\n",
                "enchantments: [null, 1, {repair: 1}, 'repairing,1']\n")) {
            Path source = write("customitems/elite_scrap_tiny.yml", yaml);
            assertTrue(OutdatedConfigurationArchive.archive(data(), storage(), selected).isEmpty());
            assertEquals(yaml, Files.readString(source));
        }
    }

    @Test void nestedValueRuleArchivesOnlyTheSelectedQuestAndPreservesOriginalBytes() throws Exception {
        String original = "# customized\r\ncustomObjectives:\r\n  Objective13:\r\n    filename: scroll_applier_config.yml\r\n";
        Path retired = write("customquests/guild/ag_welcome_quest_1.yml", original);
        Path unrelated = write("customquests/my_quest.yml", original);
        Path otherCategory = write("custombosses/ag_welcome_quest_1.yml", original);
        var selected = OutdatedConfigurationArchive.readRules(QUEST_RULE.getBytes(StandardCharsets.UTF_8));
        List<Path> archived = OutdatedConfigurationArchive.archive(data(), storage(), selected);
        assertEquals(1, archived.size());
        assertEquals(original, Files.readString(archived.getFirst()));
        assertFalse(Files.exists(retired));
        assertTrue(Files.exists(unrelated) && Files.exists(otherCategory));
        write("customquests/guild/ag_welcome_quest_1.yml", "customObjectives: {Objective14: {filename: wood_league_arena_master.yml}}\n");
        assertTrue(OutdatedConfigurationArchive.archive(data(), storage(), selected).isEmpty());
        write("customquests/guild/ag_welcome_quest_1.yml", original);
        Path reimported = OutdatedConfigurationArchive.archive(data(), storage(), selected).getFirst();
        assertNotEquals(archived.getFirst(), reimported);
        assertEquals(original, Files.readString(reimported));
    }

    @Test void nestedValueRuleRequiresAnExactStringAtTheExactMappingPath() throws Exception {
        var selected = OutdatedConfigurationArchive.readRules(QUEST_RULE.getBytes(StandardCharsets.UTF_8));
        for (String yaml : List.of(
                "# customObjectives.Objective13.filename: scroll_applier_config.yml\nname: scroll_applier_config.yml\n",
                "customObjectives.Objective13.filename: scroll_applier_config.yml\n",
                "customObjectives: {Objective12: {filename: scroll_applier_config.yml}}\n",
                "customObjectives: {Objective13: {filename: other.yml}}\n",
                "customObjectives: {Objective13: {filename: SCROLL_APPLIER_CONFIG.YML}}\n",
                "customObjectives: {Objective13: {filename: null}}\n",
                "customObjectives: {Objective13: {filename: [scroll_applier_config.yml]}}\n",
                "customObjectives: {Objective13: scroll_applier_config.yml}\n",
                "customObjectives: [{Objective13: {filename: scroll_applier_config.yml}}]\n")) {
            Path source = write("customquests/ag_welcome_quest_1.yml", yaml);
            assertTrue(OutdatedConfigurationArchive.archive(data(), storage(), selected).isEmpty(), yaml);
            assertEquals(yaml, Files.readString(source));
        }
        assertFalse(Files.exists(storage()));
    }

    @Test void rejectsUnboundedOrMalformedValueRules() {
        for (String rule : List.of("{key: a.b, value: old}",
                "{files: ['*.yml'], key: a.b, value: old}",
                "{files: [quest.yml], key: a..b, value: old}",
                "{files: [quest.yml], key: a.*.b, value: old}",
                "{files: [quest.yml], key: a.b, value: null}",
                "{files: [quest.yml], key: a.b, value: 1}",
                "{files: [quest.yml], key: a.b, value: ''}",
                "{files: [quest.yml], key: a.b, value: old, typo: true}"))
            assertThrows(IOException.class, () -> OutdatedConfigurationArchive.readRules(
                    ("customquests:\n  - " + rule + "\n").getBytes(StandardCharsets.UTF_8)), rule);
    }

    @Test void rejectsUnboundedOrMisspelledListEntryRules() {
        for (String rule : List.of("{key: enchantments, listEntryNames: [repair]}",
                "{files: ['../tiny.yml'], key: enchantments, listEntryNames: [repair]}",
                "{files: ['*.yml'], key: enchantments, listEntryNames: [repair]}",
                "{files: [tiny.yml], key: enchantments, listEntryNames: []}",
                "{files: [tiny.yml], key: enchantments, listEntryNames: [repair], typo: true}"))
            assertThrows(IOException.class, () -> OutdatedConfigurationArchive.readRules(
                    ("customitems:\n  - " + rule + "\n").getBytes(StandardCharsets.UTF_8)));
    }
}
