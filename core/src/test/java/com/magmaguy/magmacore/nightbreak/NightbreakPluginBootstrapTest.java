package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.command.AdvancedCommand;
import com.magmaguy.magmacore.command.CommandManager;
import org.bukkit.command.PluginCommand;
import org.bukkit.plugin.java.JavaPlugin;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class NightbreakPluginBootstrapTest {

    @AfterEach
    void clearSharedPluginState() {
        CommandManager.shutdown();
    }

    @Test
    void explicitFirstTimeSetupActionRegistersInitializeWithoutPresetModes() {
        JavaPlugin plugin = plugin("CannonRTP");
        CommandManager commandManager = new CommandManager(plugin, "wc");

        NightbreakPluginBootstrap.registerStandardCommands(
                plugin,
                commandManager,
                spec(false),
                player -> { },
                player -> { },
                List::of,
                sender -> { });

        assertTrue(commandManager.commands.stream()
                .map(AdvancedCommand::getAliases)
                .anyMatch(aliases -> aliases.contains("initialize")));
    }

    @Test
    void simplifiedRegistrationDoesNotInventAnInitializeActionForPresetPlugins() {
        JavaPlugin plugin = plugin("PresetPlugin");
        CommandManager commandManager = new CommandManager(plugin, "wc");

        NightbreakPluginBootstrap.registerStandardCommands(
                plugin,
                commandManager,
                spec(true),
                player -> { },
                sender -> { });

        assertTrue(commandManager.commands.stream()
                .map(AdvancedCommand::getAliases)
                .noneMatch(aliases -> aliases.contains("initialize")));
    }

    @Test
    void explicitFirstTimeSetupRegistrationRejectsAMissingAction() {
        JavaPlugin plugin = plugin("BrokenPlugin");
        CommandManager commandManager = new CommandManager(plugin, "wc");

        assertThrows(NullPointerException.class, () ->
                NightbreakPluginBootstrap.registerStandardCommands(
                        plugin,
                        commandManager,
                        spec(false),
                        player -> { },
                        null,
                        List::of,
                        sender -> { }));
    }

    private static NightbreakPluginSpec spec(boolean hasPresetModes) {
        return new NightbreakPluginSpec(
                "Test Plugin",
                "wc",
                "test.admin",
                "test.setup",
                "test.initialize",
                "https://nightbreak.io/plugin/test/",
                "Reloaded.",
                true,
                hasPresetModes,
                true);
    }

    private static JavaPlugin plugin(String name) {
        JavaPlugin plugin = mock(JavaPlugin.class);
        PluginCommand command = mock(PluginCommand.class);
        when(plugin.getName()).thenReturn(name);
        when(plugin.getCommand("wc")).thenReturn(command);
        return plugin;
    }
}
