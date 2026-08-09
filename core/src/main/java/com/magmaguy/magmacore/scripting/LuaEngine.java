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

    public static synchronized void registerScriptProvider(ScriptProvider provider) {
        Objects.requireNonNull(provider, "provider");
        Map<String, ScriptDefinition> discovered = discoverScripts(provider);
        String prefix = provider.getNamespace() + ":";
        providers.put(provider.getNamespace(), provider);
        definitions.entrySet().removeIf(
                entry -> entry.getKey().startsWith(prefix));
        definitions.putAll(discovered);
    }

    public static synchronized void unregisterScriptProvider(String namespace) {
        providers.remove(namespace);
        definitions.entrySet().removeIf(e -> e.getKey().startsWith(namespace + ":"));
    }

    public static synchronized ScriptDefinition getDefinition(String namespace, String fileName) {
        return definitions.get(namespace + ":" + fileName);
    }

    public static synchronized Collection<ScriptDefinition> getDefinitions(String namespace) {
        List<ScriptDefinition> result = new ArrayList<>();
        String prefix = namespace + ":";
        for (Map.Entry<String, ScriptDefinition> entry : definitions.entrySet()) {
            if (entry.getKey().startsWith(prefix)) result.add(entry.getValue());
        }
        return result;
    }

    public static synchronized ScriptDefinition loadScript(String namespace, File file) throws IOException {
        ScriptProvider provider = providers.get(namespace);
        if (provider == null)
            throw new IllegalStateException("No script provider registered for namespace: " + namespace);

        ScriptDefinition definition = validateScript(file, provider);
        definitions.put(namespace + ":" + file.getName(), definition);
        return definition;
    }

    public static synchronized void shutdown() {
        definitions.clear();
        providers.clear();
    }

    private static Map<String, ScriptDefinition> discoverScripts(
            ScriptProvider provider) {
        Map<String, ScriptDefinition> discovered = new LinkedHashMap<>();
        File dir = provider.getScriptDirectory().toFile();
        if (!dir.exists() || !dir.isDirectory()) return discovered;
        discoverDirectory(dir, provider, discovered);
        return discovered;
    }

    private static void discoverDirectory(
            File directory,
            ScriptProvider provider,
            Map<String, ScriptDefinition> discovered) {
        File[] files = directory.listFiles();
        if (files == null) return;
        Arrays.sort(files, Comparator.comparing(File::getName));
        for (File file : files) {
            if (file.isDirectory()) {
                discoverDirectory(file, provider, discovered);
                continue;
            }
            if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".lua")) continue;
            try {
                ScriptDefinition definition = validateScript(file, provider);
                discovered.put(
                        provider.getNamespace() + ":" + file.getName(),
                        definition);
            } catch (IOException e) {
                Logger.warn("Failed to read script: " + file.getName());
            } catch (Exception e) {
                Logger.warn("Failed to load script: " + file.getName());
                e.printStackTrace();
            }
        }
    }

    private static ScriptDefinition validateScript(
            File file, ScriptProvider provider) throws IOException {
        String source = Files.readString(
                file.toPath(), StandardCharsets.UTF_8).replace("\r", "");
        return ScriptDefinition.validate(
                file.getName(), file, source, provider);
    }
}
