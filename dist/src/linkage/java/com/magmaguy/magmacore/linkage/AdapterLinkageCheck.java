package com.magmaguy.magmacore.linkage;

import java.io.ByteArrayInputStream;
import java.io.DataInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.lang.invoke.MethodType;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Checks that the NMS adapters inside a jar link against the servers they were built for.
 *
 * <p>Each adapter module ({@code com.magmaguy.easyminecraftgoals.<module>}) is compiled against one
 * server version and then remapped to that server's runtime names. This check resolves every class,
 * field and method that an adapter class uses from {@code net.minecraft} or CraftBukkit against the
 * matching server jar. A reference that does not resolve would throw {@code NoClassDefFoundError},
 * {@code NoSuchFieldError} or {@code NoSuchMethodError} on that server. A module jar that was
 * remapped twice produces such references.
 *
 * <p>Class files are read as bytes. Nothing from the checked jar or the server jars is loaded or
 * run. A plugin jar relocates the adapters, so modules are found by their folder name.
 *
 * <p>usage: {@code AdapterLinkageCheck <jar> <report file> <module>=<server jar> ...}<br>
 * exit: 0 every reference that can be decided resolves, 1 at least one does not, 2 the check could
 * not run.
 */
public final class AdapterLinkageCheck {

    // Adapter modules are named after a server version: v1_21_R3, v1_21_R7_paper, v26.
    private static final Pattern ADAPTER_CLASS = Pattern.compile("(?:^|/)easyminecraftgoals/(v\\d[0-9A-Za-z_]*)/.+\\.class$");
    private static final String[] SERVER_PACKAGES = {"net/minecraft/", "org/bukkit/craftbukkit/"};

    private AdapterLinkageCheck() {
    }

    public static void main(String[] args) {
        int exit;
        try {
            exit = run(args);
        } catch (Exception exception) {
            System.err.println("MagmaCore adapter linkage check could not run: " + exception);
            exit = 2;
        }
        System.exit(exit);
    }

    private static int run(String[] args) throws IOException {
        if (args.length < 3) {
            throw new IllegalArgumentException("usage: AdapterLinkageCheck <jar> <report file> <module>=<server jar> ...");
        }
        Path jar = Path.of(args[0]);
        Path report = Path.of(args[1]);
        Map<String, Path> serverJars = new LinkedHashMap<>();
        for (int index = 2; index < args.length; index++) {
            int separator = args[index].indexOf('=');
            if (separator < 1) throw new IllegalArgumentException("expected <module>=<server jar>, got " + args[index]);
            serverJars.put(args[index].substring(0, separator), Path.of(args[index].substring(separator + 1)));
        }

        Map<String, Server> servers = new HashMap<>();
        Map<String, ModuleResult> modules = new TreeMap<>();
        Set<String> problems = new LinkedHashSet<>();
        try (ZipFile archive = new ZipFile(jar.toFile())) {
            for (ZipEntry entry : java.util.Collections.list(archive.entries())) {
                Matcher matcher = ADAPTER_CLASS.matcher(entry.getName());
                if (!matcher.find()) continue;
                String module = matcher.group(1);
                Path serverJar = serverJars.get(module);
                if (serverJar == null) {
                    problems.add(module + ": the jar holds this adapter module, but no server jar is declared for it");
                    serverJars.put(module, null);
                    continue;
                }
                ModuleResult result = modules.computeIfAbsent(module, name -> new ModuleResult(serverJar));
                Server server = servers.get(serverJar.toString());
                if (server == null) {
                    server = new Server(serverJar);
                    servers.put(serverJar.toString(), server);
                }
                ClassFile adapter;
                try (InputStream stream = archive.getInputStream(entry)) {
                    adapter = ClassFile.parse(stream.readAllBytes(), false);
                }
                result.classes++;
                check(module, simpleName(adapter.name), adapter, server, result);
            }
        } finally {
            for (Server server : servers.values()) server.close();
        }
        for (Map.Entry<String, Path> declared : serverJars.entrySet()) {
            if (declared.getValue() != null && !modules.containsKey(declared.getKey())) {
                problems.add(declared.getKey() + ": the jar holds no class of this adapter module");
            }
        }
        for (Map.Entry<String, ModuleResult> module : modules.entrySet()) {
            ModuleResult result = module.getValue();
            if (result.references == 0) problems.add(module.getKey() + ": no server reference was found, so nothing was checked");
            problems.addAll(result.unresolved);
        }

        List<String> lines = new ArrayList<>();
        lines.add("Adapter linkage of " + jar.getFileName());
        for (Map.Entry<String, ModuleResult> module : modules.entrySet()) {
            ModuleResult result = module.getValue();
            lines.add(String.format("  %-16s against %-34s classes=%d references=%d resolved=%d unresolved=%d undecided=%d",
                    module.getKey(), result.serverJar.getFileName(), result.classes, result.references, result.resolved,
                    result.unresolved.size(), result.undecided.size()));
        }
        if (problems.isEmpty()) {
            lines.add("OK: every reference that can be decided exists in its server jar.");
        } else {
            lines.add("FAILED: " + problems.size() + " problem(s).");
            for (String problem : problems) lines.add("  " + problem);
        }
        List<String> reportLines = new ArrayList<>(lines);
        for (Map.Entry<String, ModuleResult> module : modules.entrySet()) {
            if (module.getValue().undecided.isEmpty()) continue;
            reportLines.add("");
            reportLines.add("Undecided in " + module.getKey() + " (the member is inherited from a class outside the server jar):");
            for (String reference : module.getValue().undecided) reportLines.add("  " + reference);
        }
        if (report.getParent() != null) Files.createDirectories(report.getParent());
        Files.write(report, reportLines, StandardCharsets.UTF_8);

        if (problems.isEmpty()) {
            int references = 0;
            for (ModuleResult result : modules.values()) references += result.references;
            System.out.println("Adapter linkage OK for " + jar.getFileName() + ": " + modules.size() + " adapter modules, "
                    + references + " server references (" + report.toAbsolutePath() + ")");
            return 0;
        }
        System.err.println("MagmaCore adapter linkage check FAILED for " + jar.getFileName() + ": " + problems.size()
                + " problem(s). The adapters named below would fail on their server.");
        for (String problem : problems) System.err.println("  " + problem);
        System.err.println("A module jar that was remapped twice produces references like these. Rebuild from a clean state"
                + " (gradlew clean) and, if it happens again, look at the module's remap step.");
        System.err.println("Full report: " + report.toAbsolutePath());
        return 1;
    }

    private static void check(String module, String adapterName, ClassFile adapter, Server server, ModuleResult result)
            throws IOException {
        String where = module + " " + adapterName + ": ";
        for (String type : adapter.classConstants) {
            if (!isServerClass(type)) continue;
            result.references++;
            if (server.get(type) != null) {
                result.resolved++;
            } else {
                result.unresolved.add(where + "class " + type + " is not in " + server.name);
            }
        }
        for (Reference reference : adapter.references) {
            if (!isServerClass(reference.owner)) continue;
            result.references++;
            String text = (reference.field ? "field " : "method ") + reference.owner + "." + reference.name + ":" + reference.descriptor;
            if (server.get(reference.owner) == null) {
                result.unresolved.add(where + text + ": class " + reference.owner + " is not in " + server.name);
                continue;
            }
            Boolean found = server.resolve(reference.field, reference.owner, reference.name, reference.descriptor, new HashSet<>());
            if (found == null) {
                result.undecided.add(text);
            } else if (found) {
                result.resolved++;
            } else {
                result.unresolved.add(where + "no " + text + " in " + server.name);
            }
        }
    }

    private static boolean isServerClass(String internalName) {
        for (String prefix : SERVER_PACKAGES) {
            if (internalName.startsWith(prefix)) return true;
        }
        return false;
    }

    private static String simpleName(String internalName) {
        return internalName.substring(internalName.lastIndexOf('/') + 1);
    }

    private static final class ModuleResult {
        final Path serverJar;
        int classes;
        int references;
        int resolved;
        final Set<String> unresolved = new LinkedHashSet<>();
        final Set<String> undecided = new TreeSet<>();

        ModuleResult(Path serverJar) {
            this.serverJar = serverJar;
        }
    }

    private record Reference(boolean field, String owner, String name, String descriptor) {
    }

    /** The parts of a class file this check needs: its names, what it declares and what it uses. */
    private record ClassFile(String name, String superName, List<String> interfaces, Set<String> fields, Set<String> methods,
                             List<Reference> references, Set<String> classConstants) {

        static ClassFile parse(byte[] bytes, boolean members) throws IOException {
            DataInputStream in = new DataInputStream(new ByteArrayInputStream(bytes));
            if (in.readInt() != 0xCAFEBABE) throw new IOException("not a class file");
            in.readUnsignedShort();
            in.readUnsignedShort();
            int count = in.readUnsignedShort();
            int[] tag = new int[count];
            int[] first = new int[count];
            int[] second = new int[count];
            String[] text = new String[count];
            for (int index = 1; index < count; index++) {
                tag[index] = in.readUnsignedByte();
                switch (tag[index]) {
                    case 1 -> text[index] = in.readUTF();
                    case 3, 4 -> skip(in, 4);
                    case 5, 6 -> {
                        skip(in, 8);
                        index++;
                    }
                    case 7, 8, 16, 19, 20 -> first[index] = in.readUnsignedShort();
                    case 9, 10, 11, 12, 17, 18 -> {
                        first[index] = in.readUnsignedShort();
                        second[index] = in.readUnsignedShort();
                    }
                    case 15 -> skip(in, 3);
                    default -> throw new IOException("unknown constant pool tag " + tag[index]);
                }
            }
            in.readUnsignedShort();
            String name = text[first[in.readUnsignedShort()]];
            int superIndex = in.readUnsignedShort();
            String superName = superIndex == 0 ? null : text[first[superIndex]];
            List<String> interfaces = new ArrayList<>();
            for (int remaining = in.readUnsignedShort(); remaining > 0; remaining--) {
                interfaces.add(text[first[in.readUnsignedShort()]]);
            }
            Set<String> fields = new HashSet<>();
            Set<String> methods = new HashSet<>();
            if (members) {
                readMembers(in, text, fields);
                readMembers(in, text, methods);
            }

            List<Reference> references = new ArrayList<>();
            Set<String> classConstants = new TreeSet<>();
            for (int index = 1; index < count; index++) {
                if (tag[index] == 7) {
                    String type = elementType(text[first[index]]);
                    if (type != null) classConstants.add(type);
                } else if (tag[index] == 9 || tag[index] == 10 || tag[index] == 11) {
                    int nameAndType = second[index];
                    references.add(new Reference(tag[index] == 9, text[first[first[index]]], text[first[nameAndType]],
                            text[second[nameAndType]]));
                }
            }
            return new ClassFile(name, superName, interfaces, fields, methods, references, classConstants);
        }

        private static void readMembers(DataInputStream in, String[] text, Set<String> target) throws IOException {
            for (int remaining = in.readUnsignedShort(); remaining > 0; remaining--) {
                in.readUnsignedShort();
                String name = text[in.readUnsignedShort()];
                String descriptor = text[in.readUnsignedShort()];
                target.add(name + ":" + descriptor);
                for (int attributes = in.readUnsignedShort(); attributes > 0; attributes--) {
                    in.readUnsignedShort();
                    skip(in, in.readInt());
                }
            }
        }

        /** The class an array type holds, or the name itself; null for arrays of primitives. */
        private static String elementType(String name) {
            if (!name.startsWith("[")) return name;
            String element = name.substring(name.lastIndexOf('[') + 1);
            return element.startsWith("L") && element.endsWith(";") ? element.substring(1, element.length() - 1) : null;
        }

        private static void skip(DataInputStream in, int bytes) throws IOException {
            if (bytes < 0 || in.skipBytes(bytes) != bytes) throw new IOException("class file is truncated");
        }
    }

    /** One server jar, read class by class as references ask for them. */
    private static final class Server {
        final String name;
        private final ZipFile archive;
        private final Map<String, ClassFile> classes = new HashMap<>();

        Server(Path jar) throws IOException {
            if (!Files.isRegularFile(jar)) throw new IOException("server jar not found: " + jar);
            this.name = jar.getFileName().toString();
            this.archive = new ZipFile(jar.toFile());
        }

        ClassFile get(String internalName) throws IOException {
            if (classes.containsKey(internalName)) return classes.get(internalName);
            ZipEntry entry = archive.getEntry(internalName + ".class");
            ClassFile parsed = null;
            if (entry != null) {
                try (InputStream stream = archive.getInputStream(entry)) {
                    parsed = ClassFile.parse(stream.readAllBytes(), true);
                }
            }
            classes.put(internalName, parsed);
            return parsed;
        }

        /**
         * Looks a member up the way the JVM does: in the class, then its superclasses and
         * interfaces. TRUE and FALSE are answers; null means the search left the server jar for a
         * library class, so this jar alone cannot decide.
         */
        Boolean resolve(boolean field, String owner, String name, String descriptor, Set<String> seen) throws IOException {
            if (!seen.add(owner)) return Boolean.FALSE;
            ClassFile type = get(owner);
            if (type == null) return owner.startsWith("java/") ? jdkDeclares(field, owner, name, descriptor) : null;
            if ((field ? type.fields : type.methods).contains(name + ":" + descriptor)) return Boolean.TRUE;
            if (name.equals("<init>")) return Boolean.FALSE; // constructors are never inherited
            boolean undecided = false;
            List<String> parents = new ArrayList<>();
            if (type.superName != null) parents.add(type.superName);
            parents.addAll(type.interfaces);
            for (String parent : parents) {
                Boolean found = resolve(field, parent, name, descriptor, seen);
                if (found == null) undecided = true;
                else if (found) return Boolean.TRUE;
            }
            return undecided ? null : Boolean.FALSE;
        }

        void close() throws IOException {
            archive.close();
        }
    }

    /** A JDK class is not in the server jar, but the running JDK can answer for it. */
    private static Boolean jdkDeclares(boolean field, String owner, String name, String descriptor) {
        Class<?> type;
        try {
            type = Class.forName(owner.replace('/', '.'), false, ClassLoader.getPlatformClassLoader());
        } catch (ClassNotFoundException | LinkageError unavailable) {
            return null;
        }
        return jdkDeclares(field, type, name, descriptor, new HashSet<>());
    }

    private static boolean jdkDeclares(boolean field, Class<?> type, String name, String descriptor, Set<Class<?>> seen) {
        if (type == null || !seen.add(type)) return false;
        if (field) {
            for (Field candidate : type.getDeclaredFields()) {
                if (candidate.getName().equals(name) && candidate.getType().descriptorString().equals(descriptor)) return true;
            }
        } else {
            for (Method candidate : type.getDeclaredMethods()) {
                if (candidate.getName().equals(name) && MethodType.methodType(candidate.getReturnType(),
                        candidate.getParameterTypes()).toMethodDescriptorString().equals(descriptor)) {
                    return true;
                }
            }
        }
        if (jdkDeclares(field, type.getSuperclass(), name, descriptor, seen)) return true;
        for (Class<?> parent : type.getInterfaces()) {
            if (jdkDeclares(field, parent, name, descriptor, seen)) return true;
        }
        // An interface also answers for Object's public methods.
        return !field && type.isInterface() && jdkDeclares(false, Object.class, name, descriptor, seen);
    }
}
