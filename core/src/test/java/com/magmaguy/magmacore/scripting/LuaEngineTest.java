package com.magmaguy.magmacore.scripting;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class LuaEngineTest {

    @TempDir
    Path scriptDirectory;

    @AfterEach
    void shutDownEngine() {
        LuaEngine.shutdown();
    }

    @Test
    void reRegisteringProviderReplacesItsCompleteDefinitionSet() throws Exception {
        Path retained = writeScript("retained.lua", "on_spawn");
        Path removed = writeScript("removed.lua", "on_game_tick");
        ScriptProvider provider = provider("fixture");

        LuaEngine.registerScriptProvider(provider);

        assertNotNull(LuaEngine.getDefinition("fixture", retained.getFileName().toString()));
        assertNotNull(LuaEngine.getDefinition("fixture", removed.getFileName().toString()));
        assertEquals(2, LuaEngine.getDefinitions("fixture").size());

        Files.delete(removed);
        LuaEngine.registerScriptProvider(provider);

        assertNotNull(LuaEngine.getDefinition("fixture", retained.getFileName().toString()));
        assertNull(LuaEngine.getDefinition("fixture", removed.getFileName().toString()),
                "a script removed from disk must not survive provider reload");
        assertEquals(1, LuaEngine.getDefinitions("fixture").size());
    }

    @Test
    void replacingOneNamespaceDoesNotDeleteAnotherNamespace() throws Exception {
        writeScript("shared.lua", "on_spawn");
        LuaEngine.registerScriptProvider(provider("first"));
        LuaEngine.registerScriptProvider(provider("second"));

        Files.delete(scriptDirectory.resolve("shared.lua"));
        LuaEngine.registerScriptProvider(provider("first"));

        assertNull(LuaEngine.getDefinition("first", "shared.lua"));
        assertNotNull(LuaEngine.getDefinition("second", "shared.lua"));
    }

    private Path writeScript(String fileName, String hook) throws Exception {
        Path path = scriptDirectory.resolve(fileName);
        Files.writeString(path,
                "return { api_version = 1, " + hook + " = function() end }\n",
                StandardCharsets.UTF_8);
        return path;
    }

    private ScriptProvider provider(String namespace) {
        return new ScriptProvider() {
            @Override
            public String getNamespace() {
                return namespace;
            }

            @Override
            public Path getScriptDirectory() {
                return scriptDirectory;
            }

            @Override
            public ScriptHook resolveHook(String key) {
                return Set.of("on_spawn", "on_game_tick").contains(key)
                        ? new ScriptHook(key)
                        : null;
            }
        };
    }
}
