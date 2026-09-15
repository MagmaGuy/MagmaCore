package com.magmaguy.magmacore.util;

import org.bukkit.World;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/** Inspects one exported dimension and copies it without modifying the source. */
public final class WorldBlueprint {
    private enum Layout { LEGACY_WORLD, LEGACY_TERRAIN, MODERN_DIMENSION }
    private static final Set<String> IDENTITY_FILES = Set.of("uid.dat", "session.lock");
    private final Path root;
    private final Path terrain;
    private final Layout layout;

    private WorldBlueprint(Path root, Path terrain, Layout layout) {
        this.root = root;
        this.terrain = terrain;
        this.layout = layout;
    }

    public static WorldBlueprint inspect(Path root, World.Environment environment) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Blueprint directory is missing: " + root);
        if (Files.exists(root.resolve("dimensions")))
            throw new IOException("Export a single dimension, not the server's dimensions container: " + root);
        // Reject links before any destination is created, including links outside the selected dimension.
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path path, BasicFileAttributes attrs) throws IOException {
                if (Files.isSymbolicLink(path) || attrs.isOther()) throw new IOException("Linked blueprint directory: " + path);
                return FileVisitResult.CONTINUE;
            }
            @Override public FileVisitResult visitFile(Path path, BasicFileAttributes attrs) throws IOException {
                if (!attrs.isRegularFile() || Files.isSymbolicLink(path)) throw new IOException("Non-regular blueprint file: " + path);
                return FileVisitResult.CONTINUE;
            }
        });
        List<Path> candidates = new ArrayList<>();
        for (Path candidate : List.of(root, root.resolve("DIM-1"), root.resolve("DIM1"))) {
            if (hasRegions(candidate)) candidates.add(candidate);
        }
        if (candidates.size() != 1)
            throw new IOException("Expected one terrain dimension with region files, found " + candidates.size() + ": " + root);
        Path terrain = candidates.getFirst();
        boolean metadata = Files.isRegularFile(root.resolve("level.dat")) || Files.isRegularFile(root.resolve("level.dat_old"));
        boolean modern = !metadata && (Files.isDirectory(root.resolve("data/paper")) || Files.isDirectory(root.resolve("data/minecraft")));
        if ((metadata && Files.isRegularFile(root.resolve("data/paper/metadata.dat"))) || (modern && !terrain.equals(root)))
            throw new IOException("Mixed legacy and modern blueprint layout: " + root);
        if (!terrain.equals(root) && !terrain.equals(root.resolve(legacyDimension(environment))))
            throw new IOException("Blueprint dimension does not match configured environment " + environment + ": " + root);
        return new WorldBlueprint(root, terrain, modern ? Layout.MODERN_DIMENSION : metadata ? Layout.LEGACY_WORLD : Layout.LEGACY_TERRAIN);
    }

    private static boolean hasRegions(Path directory) throws IOException {
        Path region = directory.resolve("region");
        if (!Files.isDirectory(region)) return false;
        try (var files = Files.list(region)) {
            return files.anyMatch(file -> Files.isRegularFile(file) && file.getFileName().toString().matches("r\\.-?\\d+\\.-?\\d+\\.mca"));
        }
    }

    private static String legacyDimension(World.Environment environment) throws IOException {
        return switch (environment) {
            case NORMAL -> "";
            case NETHER -> "DIM-1";
            case THE_END -> "DIM1";
            default -> throw new IOException("Unsupported blueprint environment: " + environment);
        };
    }

    public void requireCompatibleServer(boolean modernServer) throws IOException {
        if (layout == Layout.MODERN_DIMENSION && !modernServer)
            throw new IOException("Modern dimension exports require a server using modern dimension storage: " + root);
    }

    /** Complete legacy worlds retain Paper's native migration; terrain-only inputs use the live layout. */
    public boolean usesModernDestination(boolean modernServer) throws IOException {
        requireCompatibleServer(modernServer);
        return modernServer && layout != Layout.LEGACY_WORLD;
    }

    public void copyTo(Path destination, World.Environment environment, boolean modernServer) throws IOException {
        requireCompatibleServer(modernServer);
        Path source = layout == Layout.LEGACY_TERRAIN ? terrain : root;
        String child = layout == Layout.LEGACY_TERRAIN && !modernServer ? legacyDimension(environment) : "";
        // CREATE_NEW semantics protect existing worlds and competing instance preparations.
        Files.createDirectories(destination.getParent());
        Files.createDirectory(destination);
        try {
            Path output = destination.resolve(child);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(directory) || attrs.isOther()) throw new IOException("Linked blueprint directory: " + directory);
                    Files.createDirectories(output.resolve(source.relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("Non-regular blueprint file: " + file);
                    Path relative = source.relativize(file);
                    String portable = relative.toString().replace('\\', '/');
                    if (IDENTITY_FILES.contains(portable) || portable.equals("data/paper/metadata.dat")
                            || portable.equals("data/paper/metadata.dat_old")) return FileVisitResult.CONTINUE;
                    Files.copy(file, output.resolve(relative), StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException failure) {
            try {
                Files.walkFileTree(destination, new SimpleFileVisitor<>() {
                    @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                        Files.delete(file); return FileVisitResult.CONTINUE;
                    }
                    @Override public FileVisitResult postVisitDirectory(Path directory, IOException problem) throws IOException {
                        if (problem != null) throw problem;
                        Files.delete(directory); return FileVisitResult.CONTINUE;
                    }
                });
            } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }
}
