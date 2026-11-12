package com.magmaguy.magmacore.ai.overlay;

import com.magmaguy.magmacore.scripting.LuaMindDefinition;
import com.magmaguy.magmacore.scripting.LuaMindModuleDescriptor;
import com.magmaguy.magmacore.scripting.LuaMindModuleRegistry;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The mob behavior modules of one plugin: every {@code .lua} file in its behaviors folder, each an
 * {@code ai.module} whose id is {@code <namespace>:mob/<file name>}, and the programs composed from them.
 */
final class MindOverlayCatalog implements AutoCloseable {
    // The soft scheduling budget and hard runaway limits every overlay program runs under: FMM's pet programs',
    // which share the host and its per-tick server budget with these.
    private static final String LIMITS =
            "budget = { callback_micros = 1500, entity_micros = 3000, server_micros = 10000, "
                    + "max_callbacks = 32, max_action_requests = 1 }, "
                    + "runaway = { cpu_micros = 20000, max_instructions = 20000 }";
    private static final Pattern NAME = Pattern.compile("[a-z0-9._-]+");

    private final String namespace;
    private final LuaMindModuleRegistry registry = new LuaMindModuleRegistry();
    private final LuaMindModuleRegistry.OwnerScope owner;
    private final Map<String, LuaMindModuleDescriptor> behaviors = new TreeMap<>();
    private final Map<List<String>, Program> programs = new HashMap<>();

    private MindOverlayCatalog(String namespace) {
        this.namespace = namespace;
        owner = registry.openOwner(namespace);
    }

    /** Reads every {@code .lua} file in the folder and under it. A folder that does not exist has no behaviors. */
    static MindOverlayCatalog read(String namespace, Path folder, Consumer<String> warn) {
        Map<String, String> sources = new TreeMap<>();
        if (Files.isDirectory(folder)) {
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path file : files.filter(Files::isRegularFile).sorted().toList()) {
                    String fileName = file.getFileName().toString();
                    if (!fileName.toLowerCase(Locale.ROOT).endsWith(".lua")) continue;
                    String name = fileName.substring(0, fileName.length() - 4);
                    if (sources.containsKey(name)) {
                        warn.accept("Mob behavior " + folder.relativize(file) + " has the same name as another file"
                                + " in the folder and was left out.");
                        continue;
                    }
                    sources.put(name, Files.readString(file, StandardCharsets.UTF_8));
                }
            } catch (IOException | RuntimeException exception) {
                warn.accept("Could not read the mob behaviors in " + folder + ": " + exception.getMessage());
            }
        }
        return of(namespace, sources, warn);
    }

    /** A catalog of the given sources by file name without extension. */
    static MindOverlayCatalog of(String namespace, Map<String, String> sources, Consumer<String> warn) {
        MindOverlayCatalog catalog = new MindOverlayCatalog(namespace);
        // A module registers only once the ones it depends on have, so it is offered again each pass.
        Map<String, String> waiting = new LinkedHashMap<>(sources);
        Map<String, String> problems = new LinkedHashMap<>();
        boolean progressed = true;
        while (progressed && !waiting.isEmpty()) {
            progressed = false;
            for (Map.Entry<String, String> entry : new ArrayList<>(waiting.entrySet())) {
                String name = entry.getKey();
                String problem = catalog.register(name, entry.getValue());
                if (problem == null) {
                    waiting.remove(name);
                    problems.remove(name);
                    progressed = true;
                } else {
                    problems.put(name, problem);
                }
            }
        }
        problems.forEach((name, problem) ->
                warn.accept("Mob behavior " + name + ".lua was left out: " + problem));
        return catalog;
    }

    /** Null when the module is registered, otherwise why it is not. */
    private String register(String name, String source) {
        if (!NAME.matcher(name).matches()) {
            return "its file name may only have lower-case letters, digits, dots, dashes and underscores";
        }
        String expected = moduleId(name);
        try {
            LuaMindModuleDescriptor inspected = LuaMindModuleRegistry.inspectModule(name + ".lua", source);
            if (!expected.equals(inspected.identifier())) {
                return "its id must be '" + expected + "' to match its file name, not '"
                        + inspected.identifier() + "'";
            }
            behaviors.put(name, owner.registerModule(expected, inspected.revision(), name + ".lua", source));
            return null;
        } catch (RuntimeException exception) {
            return exception.getMessage() == null ? exception.getClass().getSimpleName() : exception.getMessage();
        }
    }

    private String moduleId(String name) {
        return namespace + ":mob/" + name;
    }

    /** The behavior names that loaded, in alphabetical order. */
    List<String> behaviors() {
        return List.copyOf(behaviors.keySet());
    }

    /**
     * The program made of these behaviors, composed once and kept.
     *
     * @throws IllegalArgumentException when a name is unknown or the modules cannot run together, with the reason
     */
    Program program(List<String> names) {
        Program known = programs.get(names);
        if (known != null) return known;
        List<String> ids = new ArrayList<>(names.size());
        for (String name : names) {
            if (!behaviors.containsKey(name)) {
                throw new IllegalArgumentException("There is no mob behavior '" + name + "'. Loaded: "
                        + (behaviors.isEmpty() ? "none" : String.join(", ", behaviors.keySet())));
            }
            ids.add("'" + moduleId(name) + "'");
        }
        // One program per combination of modules, named for it so two never share an identity.
        String identifier = namespace + ":mob/overlay/"
                + Integer.toUnsignedString(String.join(",", names).hashCode(), 36);
        String source = "return ai.program { id = '" + identifier + "', revision = 1, modules = { "
                + String.join(", ", ids) + " }, " + LIMITS + " }";
        LuaMindDefinition definition = owner.validateProgram(String.join("+", names), source);
        Map<String, LuaMindModuleDescriptor> modules = new LinkedHashMap<>();
        for (LuaMindModuleDescriptor module : definition.resolvedModules()) modules.put(module.identifier(), module);
        Program program = new Program(definition, Map.copyOf(modules), namespace + ":mob/");
        programs.put(List.copyOf(names), program);
        return program;
    }

    @Override
    public void close() {
        programs.clear();
        behaviors.clear();
        registry.close();
    }

    /**
     * @param modules  every module the program runs, by id, including the ones its behaviors depend on
     * @param idPrefix what a behavior's name is prefixed with to make its module id
     */
    record Program(LuaMindDefinition definition, Map<String, LuaMindModuleDescriptor> modules, String idPrefix) {
    }
}
