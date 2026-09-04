package com.magmaguy.magmacore.scripting;

import com.magmaguy.magmacore.ai.MindProgram;
import com.magmaguy.magmacore.ai.MindProgramFactory;
import org.luaj.vm2.Globals;
import org.luaj.vm2.LuaTable;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/**
 * Validated Lua source that creates a fresh Mind program and Lua VM per entity binding.
 * Module-composed definitions retain their exact dependency-first source snapshot and fingerprint;
 * instantiation never consults a live module registry.
 */
public final class LuaMindDefinition implements MindProgramFactory {
    private final String sourceName;
    private final String source;
    private final String programIdentifier;
    private final long programRevision;
    private final List<String> rootModules;
    private final List<LuaMindModuleSource> moduleSources;
    private final List<LuaMindModuleDescriptor> resolvedModules;
    private final String programFingerprint;

    private LuaMindDefinition(
            String sourceName,
            String source,
            String programIdentifier,
            long programRevision,
            List<String> rootModules,
            List<LuaMindModuleSource> moduleSources,
            String programFingerprint) {
        this.sourceName = sourceName;
        this.source = source;
        this.programIdentifier = programIdentifier;
        this.programRevision = programRevision;
        this.rootModules = List.copyOf(rootModules);
        this.moduleSources = List.copyOf(moduleSources);
        this.resolvedModules = moduleSources.stream()
                .map(LuaMindModuleSource::descriptor)
                .toList();
        this.programFingerprint = programFingerprint;
    }

    public static LuaMindDefinition validate(String sourceName, String source) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(source, "source");
        LuaMindProgramMetadata metadata = LuaMindSourceReader.inspectProgram(sourceName, source);
        if (!metadata.modules().isEmpty()) {
            throw new IllegalArgumentException(
                    "Mind program " + metadata.identifier()
                            + " declares modules and must be validated through a module owner");
        }
        return validateResolved(sourceName, source, metadata, List.of());
    }

    static LuaMindDefinition validateResolved(
            String sourceName,
            String source,
            LuaMindProgramMetadata metadata,
            List<LuaMindModuleSource> moduleSources) {
        Objects.requireNonNull(sourceName, "sourceName");
        Objects.requireNonNull(source, "source");
        Objects.requireNonNull(metadata, "metadata");
        List<LuaMindModuleSource> snapshot = List.copyOf(moduleSources);
        String fingerprint = fingerprint(metadata, source, snapshot);
        MindProgram program = evaluate(sourceName, source, metadata, snapshot);
        return new LuaMindDefinition(
                sourceName,
                source,
                program.identifier(),
                program.revision(),
                metadata.modules(),
                snapshot,
                fingerprint);
    }

    @Override
    public MindProgram instantiate() {
        return evaluate(
                sourceName,
                source,
                new LuaMindProgramMetadata(
                        programIdentifier,
                        programRevision,
                        rootModules),
                moduleSources);
    }

    public String sourceName() {
        return sourceName;
    }

    public String programIdentifier() {
        return programIdentifier;
    }

    public long programRevision() {
        return programRevision;
    }

    /** Dependency-first module identities and revisions captured by this definition. */
    public List<LuaMindModuleDescriptor> resolvedModules() {
        return resolvedModules;
    }

    /** Stable SHA-256 identity of the program source and its exact transitive module snapshot. */
    public String programFingerprint() {
        return programFingerprint;
    }

    private static MindProgram evaluate(
            String sourceName,
            String source,
            LuaMindProgramMetadata expectedMetadata,
            List<LuaMindModuleSource> moduleSources) {
        return LuaExecutionBudget.run(() -> {
            Globals globals = LuaEnvironmentFactory.createGlobals();
            List<LuaTable> modules = new ArrayList<>(moduleSources.size());
            for (LuaMindModuleSource module : moduleSources) {
                modules.add(module.evaluate(globals));
            }
            LuaTable table = LuaMindSourceReader.evaluateTable(
                    globals,
                    sourceName,
                    source,
                    "program");
            LuaMindProgramMetadata actualMetadata = LuaMindSourceReader.programMetadata(table);
            if (!expectedMetadata.equals(actualMetadata)) {
                throw new IllegalArgumentException(
                        "Mind program " + sourceName + " produced non-deterministic metadata");
            }
            MindProgram program = LuaMindProgramCompiler.compile(table, modules);
            if (!program.identifier().equals(expectedMetadata.identifier())
                    || program.revision() != expectedMetadata.revision()) {
                throw new IllegalArgumentException(
                        "Mind program " + sourceName + " did not compile to its inspected identity");
            }
            return program;
        });
    }

    private static String fingerprint(
            LuaMindProgramMetadata metadata,
            String source,
            List<LuaMindModuleSource> modules) {
        MessageDigest digest;
        try {
            digest = MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("JVM does not provide SHA-256", exception);
        }
        update(digest, "magmacore-lua-mind-composition-v1");
        update(digest, metadata.identifier());
        update(digest, metadata.revision());
        update(digest, metadata.modules().size());
        for (String rootModule : metadata.modules()) update(digest, rootModule);
        update(digest, source);
        update(digest, modules.size());
        for (LuaMindModuleSource module : modules) {
            update(digest, module.identifier());
            update(digest, module.revision());
            update(digest, module.dependencies().size());
            for (String dependency : module.dependencies()) update(digest, dependency);
            update(digest, module.source());
        }
        return HexFormat.of().formatHex(digest.digest());
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(bytes.length).array());
        digest.update(bytes);
    }

    private static void update(MessageDigest digest, long value) {
        digest.update(ByteBuffer.allocate(Long.BYTES).putLong(value).array());
    }

    private static void update(MessageDigest digest, int value) {
        digest.update(ByteBuffer.allocate(Integer.BYTES).putInt(value).array());
    }
}
