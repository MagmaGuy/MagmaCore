package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.util.Logger;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

public final class LuaEngine {
    private static final Map<String, ScriptProvider> providers = new ConcurrentHashMap<>();
    private static final Map<String, ScriptDefinition> definitions = new ConcurrentHashMap<>();

    private LuaEngine() {}

    public static void registerScriptProvider(ScriptProvider provider) {
        providers.put(provider.getNamespace(), provider);
        discoverScripts(provider);
    }

    public static void unregisterScriptProvider(String namespace) {
        providers.remove(namespace);
        definitions.entrySet().removeIf(e -> e.getKey().startsWith(namespace + ":"));
    }

    public static ScriptDefinition getDefinition(String namespace, String fileName) {
        return definitions.get(namespace + ":" + fileName);
    }

    public static Collection<ScriptDefinition> getDefinitions(String namespace) {
        List<ScriptDefinition> result = new ArrayList<>();
        String prefix = namespace + ":";
        for (Map.Entry<String, ScriptDefinition> entry : definitions.entrySet()) {
            if (entry.getKey().startsWith(prefix)) result.add(entry.getValue());
        }
        return result;
    }

    public static ScriptDefinition loadScript(String namespace, File file) throws IOException {
        ScriptProvider provider = providers.get(namespace);
        if (provider == null)
            throw new IllegalStateException("No script provider registered for namespace: " + namespace);

        String source = Files.readString(file.toPath(), StandardCharsets.UTF_8).replace("\r", "");
        ScriptDefinition definition = ScriptDefinition.validate(file.getName(), file, source, provider);
        definitions.put(namespace + ":" + file.getName(), definition);
        return definition;
    }

    public static void shutdown() {
        definitions.clear();
        providers.clear();
    }

    private static void discoverScripts(ScriptProvider provider) {
        File dir = provider.getScriptDirectory().toFile();
        if (!dir.exists() || !dir.isDirectory()) return;
        discoverDirectory(dir, provider);
    }

    private static void discoverDirectory(File directory, ScriptProvider provider) {
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (file.isDirectory()) {
                discoverDirectory(file, provider);
                continue;
            }
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".lua")) continue;
            try {
                loadScript(provider.getNamespace(), file);
            } catch (IOException e) {
                Logger.warn("Failed to read script: " + file.getName());
            } catch (Exception e) {
                Logger.warn("Failed to load script: " + file.getName());
                e.printStackTrace();
            }
        }
    }
}
