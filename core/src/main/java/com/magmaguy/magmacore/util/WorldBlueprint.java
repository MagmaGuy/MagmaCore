package com.magmaguy.magmacore.util;

import com.magmaguy.magmacore.MagmaCore;
import org.bukkit.World;
import org.bukkit.WorldBorder;

import java.io.DataInputStream;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Stream;
import java.util.zip.GZIPInputStream;

/**
 * Inspects one exported dimension, copies it without modifying the source, and replaces it with the
 * server's own conversion of it.
 */
public final class WorldBlueprint {
    /**
     * LEGACY_WORLD and LEGACY_TERRAIN keep terrain at the root or in DIM-1/DIM1. VANILLA_WORLD is a world
     * saved by Minecraft 26.1+ outside Paper's dimension storage, with terrain under dimensions/.
     * MODERN_DIMENSION is one Paper 26.1+ dimension folder.
     */
    private enum Layout { LEGACY_WORLD, LEGACY_TERRAIN, MODERN_DIMENSION, VANILLA_WORLD }
    private static final Set<Path> IDENTITY_FILES = Set.of(
            Path.of("uid.dat"), Path.of("session.lock"),
            Path.of("data", "paper", "metadata.dat"), Path.of("data", "paper", "metadata.dat_old"));
    // level.dat_old still holds the level data from before the conversion saved it.
    private static final List<Path> CONVERSION_LEFTOVERS = List.of(
            Path.of("uid.dat"), Path.of("session.lock"), Path.of("level.dat_old"),
            Path.of("data", "paper", "metadata.dat"), Path.of("data", "paper", "metadata.dat_old"));
    private static final Set<String> BORDER_FIELDS = Set.of("BorderCenterX", "BorderCenterZ", "BorderSize",
            "BorderDamagePerBlock", "BorderSafeZone", "BorderWarningBlocks", "BorderWarningTime");
    private static final double DEFAULT_BORDER_SIZE = 5.9999968E7;
    private final Path root;
    private final Path terrain;
    private final Layout layout;
    private final World.Environment environment;

    private WorldBlueprint(Path root, Path terrain, Layout layout, World.Environment environment) {
        this.root = root;
        this.terrain = terrain;
        this.layout = layout;
        this.environment = environment;
    }

    public static WorldBlueprint inspect(Path root, World.Environment environment) throws IOException {
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS))
            throw new IOException("Blueprint directory is missing: " + root);
        boolean metadata = Files.isRegularFile(root.resolve("level.dat")) || Files.isRegularFile(root.resolve("level.dat_old"));
        // A saved world owns its dimensions folder; without level data it is the server's dimensions container.
        boolean vanilla = Files.exists(root.resolve("dimensions"));
        if (vanilla && !metadata)
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
        if (metadata && Files.isRegularFile(root.resolve("data").resolve("paper").resolve("metadata.dat")))
            throw new IOException("Mixed legacy and modern blueprint layout: " + root);
        List<Path> legacyCandidates = new ArrayList<>();
        for (Path candidate : List.of(root, root.resolve("DIM-1"), root.resolve("DIM1"))) {
            if (hasRegions(candidate)) legacyCandidates.add(candidate);
        }
        if (vanilla) {
            List<Path> candidates = new ArrayList<>();
            for (World.Environment dimension : List.of(World.Environment.NORMAL, World.Environment.NETHER, World.Environment.THE_END)) {
                Path candidate = vanillaDimension(root, dimension);
                if (hasRegions(candidate)) candidates.add(candidate);
            }
            if (candidates.size() != 1 || !legacyCandidates.isEmpty())
                throw new IOException("Expected one terrain dimension with region files, found "
                        + (candidates.size() + legacyCandidates.size()) + ": " + root);
            if (!candidates.getFirst().equals(vanillaDimension(root, environment)))
                throw new IOException("Blueprint dimension does not match configured environment " + environment + ": " + root);
            return new WorldBlueprint(root, candidates.getFirst(), Layout.VANILLA_WORLD, environment);
        }
        if (legacyCandidates.size() != 1)
            throw new IOException("Expected one terrain dimension with region files, found " + legacyCandidates.size() + ": " + root);
        Path terrain = legacyCandidates.getFirst();
        boolean modern = !metadata && (Files.isDirectory(root.resolve("data").resolve("paper"))
                || Files.isDirectory(root.resolve("data").resolve("minecraft")));
        if (modern && !terrain.equals(root))
            throw new IOException("Mixed legacy and modern blueprint layout: " + root);
        if (!terrain.equals(root) && !terrain.equals(root.resolve(legacyDimension(environment))))
            throw new IOException("Blueprint dimension does not match configured environment " + environment + ": " + root);
        return new WorldBlueprint(root, terrain, modern ? Layout.MODERN_DIMENSION : metadata ? Layout.LEGACY_WORLD : Layout.LEGACY_TERRAIN, environment);
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

    private static Path vanillaDimension(Path root, World.Environment environment) throws IOException {
        String stem = switch (environment) {
            case NORMAL -> "overworld";
            case NETHER -> "the_nether";
            case THE_END -> "the_end";
            default -> throw new IOException("Unsupported blueprint environment: " + environment);
        };
        return root.resolve("dimensions").resolve("minecraft").resolve(stem);
    }

    public void requireCompatibleServer(boolean modernServer) throws IOException {
        if (layout == Layout.MODERN_DIMENSION && !modernServer)
            throw new IOException("Modern dimension exports require a server using modern dimension storage: " + root);
    }

    /** Worlds with their own level data retain Paper's native migration; terrain-only inputs use the live layout. */
    public boolean usesModernDestination(boolean modernServer) throws IOException {
        requireCompatibleServer(modernServer);
        return modernServer && layout != Layout.LEGACY_WORLD && layout != Layout.VANILLA_WORLD;
    }

    /**
     * Whether this server rebuilds the blueprint's storage for every copy it loads: Paper 26.1+ imports any world
     * with its own level data into its dimension storage, and every server upgrades level data an older version saved.
     * Terrain-only blueprints have no level data to convert.
     */
    public boolean needsConversion(boolean modernServer, int serverDataVersion) {
        if (layout == Layout.MODERN_DIMENSION || layout == Layout.LEGACY_TERRAIN) return false;
        if (modernServer) return true;
        int dataVersion = levelDataVersion();
        return dataVersion >= 0 && dataVersion < serverDataVersion;
    }

    /** The DataVersion level.dat was saved with, or -1 when there is none or it cannot be read. */
    int levelDataVersion() {
        Path file = root.resolve("level.dat");
        if (!Files.isRegularFile(file)) file = root.resolve("level.dat_old");
        if (!Files.isRegularFile(file)) return -1;
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            return readDataVersion(input);
        } catch (IOException | RuntimeException unreadable) {
            return -1;
        }
    }

    /**
     * The world border level.dat sets, which is the border the blueprint's author saw. From Minecraft 1.21.9 servers
     * keep the border per dimension and convert level.dat's into it, and some of those conversions divide a nether
     * world's center by the nether's coordinate scale, which moves the border off the map.
     *
     * @return the border, or null when level.dat sets none (newer saves keep it elsewhere) or cannot be read
     */
    public LevelBorder levelBorder() {
        Path file = root.resolve("level.dat");
        if (!Files.isRegularFile(file)) file = root.resolve("level.dat_old");
        if (!Files.isRegularFile(file)) return null;
        Map<String, Double> fields;
        try (DataInputStream input = new DataInputStream(new GZIPInputStream(Files.newInputStream(file)))) {
            fields = readLevelDoubles(input, BORDER_FIELDS);
        } catch (IOException | RuntimeException unreadable) {
            return null;
        }
        Double size = fields.get("BorderSize"), centerX = fields.get("BorderCenterX"), centerZ = fields.get("BorderCenterZ");
        if (size == null || centerX == null || centerZ == null) return null;
        // Vanilla's default border is no border at all.
        if (size >= DEFAULT_BORDER_SIZE && centerX == 0 && centerZ == 0) return null;
        return new LevelBorder(centerX, centerZ, size, fields.getOrDefault("BorderDamagePerBlock", 0.2),
                fields.getOrDefault("BorderSafeZone", 5.0), fields.getOrDefault("BorderWarningBlocks", 5.0),
                fields.getOrDefault("BorderWarningTime", 15.0));
    }

    /** A world border as level.dat stores it. */
    public record LevelBorder(double centerX, double centerZ, double size, double damagePerBlock, double safeZone,
                              double warningBlocks, double warningTime) {
        public void applyTo(World world) {
            WorldBorder border = world.getWorldBorder();
            border.setCenter(centerX, centerZ);
            border.setSize(size);
            border.setDamageAmount(damagePerBlock);
            border.setDamageBuffer(safeZone);
            border.setWarningDistance((int) warningBlocks);
            border.setWarningTime((int) warningTime);
        }
    }

    // Collects the named double fields of level.dat's Data compound.
    private static Map<String, Double> readLevelDoubles(DataInputStream input, Set<String> names) throws IOException {
        Map<String, Double> values = new HashMap<>();
        if (input.readByte() != 10) return values;
        input.readUTF();
        for (byte type = input.readByte(); type != 0; type = input.readByte()) {
            String name = input.readUTF();
            if (type != 10 || !name.equals("Data")) {
                skipTag(input, type);
                continue;
            }
            for (byte field = input.readByte(); field != 0; field = input.readByte()) {
                String key = input.readUTF();
                if (field == 6 && names.contains(key)) values.put(key, input.readDouble());
                else skipTag(input, field);
            }
            return values;
        }
        return values;
    }

    // level.dat is a gzipped NBT compound; its Data compound records the DataVersion that saved it.
    private static int readDataVersion(DataInputStream input) throws IOException {
        if (input.readByte() != 10) return -1;
        input.readUTF();
        for (byte type = input.readByte(); type != 0; type = input.readByte()) {
            String name = input.readUTF();
            if (type != 10 || !name.equals("Data")) {
                skipTag(input, type);
                continue;
            }
            for (byte field = input.readByte(); field != 0; field = input.readByte()) {
                String key = input.readUTF();
                if (field == 3 && key.equals("DataVersion")) return input.readInt();
                skipTag(input, field);
            }
            return -1;
        }
        return -1;
    }

    private static void skipTag(DataInputStream input, byte type) throws IOException {
        switch (type) {
            case 1 -> input.skipNBytes(1);
            case 2 -> input.skipNBytes(2);
            case 3, 5 -> input.skipNBytes(4);
            case 4, 6 -> input.skipNBytes(8);
            case 7 -> input.skipNBytes(input.readInt());
            case 8 -> input.skipNBytes(input.readUnsignedShort());
            case 9 -> {
                byte elementType = input.readByte();
                int length = input.readInt();
                for (int i = 0; i < length; i++) skipTag(input, elementType);
            }
            case 10 -> {
                for (byte field = input.readByte(); field != 0; field = input.readByte()) {
                    input.skipNBytes(input.readUnsignedShort());
                    skipTag(input, field);
                }
            }
            case 11 -> input.skipNBytes(4L * input.readInt());
            case 12 -> input.skipNBytes(8L * input.readInt());
            default -> throw new IOException("Unknown NBT tag type " + type);
        }
    }

    public void copyTo(Path destination, World.Environment environment, boolean modernServer) throws IOException {
        copyTo(destination, environment, modernServer, CopyListener.NONE);
    }

    public void copyTo(Path destination, World.Environment environment, boolean modernServer,
                       CopyListener listener) throws IOException {
        requireCompatibleServer(modernServer);
        Path source = layout == Layout.LEGACY_TERRAIN ? terrain : root;
        String child = layout == Layout.LEGACY_TERRAIN && !modernServer ? legacyDimension(environment) : "";
        copyTree(source, destination, destination.resolve(child), listener);
    }

    /** Follows a copy into a new world folder. Either step can throw to stop the copy, which then removes the folder. */
    public interface CopyListener {
        CopyListener NONE = new CopyListener() {};

        /** The new world folder exists and is still empty. */
        default void created(Path destination) throws IOException {}

        /** Runs before each blueprint file is copied. */
        default void copying(Path file) throws IOException {}
    }

    /**
     * Copies the blueprint into the world folder the server loads and saves to convert it. Only blueprints with
     * their own level data are converted.
     */
    public void copyForConversion(Path worldFolder) throws IOException {
        if (layout != Layout.LEGACY_WORLD && layout != Layout.VANILLA_WORLD)
            throw new IOException("Only blueprints with their own level data are converted: " + root);
        copyTree(root, worldFolder, worldFolder, CopyListener.NONE);
    }

    private static void copyTree(Path source, Path destination, Path output, CopyListener listener) throws IOException {
        // CREATE_NEW semantics protect existing worlds and competing instance preparations.
        Files.createDirectories(destination.getParent());
        Files.createDirectory(destination);
        try {
            listener.created(destination);
            Files.walkFileTree(source, new SimpleFileVisitor<>() {
                @Override public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs) throws IOException {
                    if (Files.isSymbolicLink(directory) || attrs.isOther()) throw new IOException("Linked blueprint directory: " + directory);
                    Files.createDirectories(output.resolve(source.relativize(directory)));
                    return FileVisitResult.CONTINUE;
                }
                @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                    if (!attrs.isRegularFile() || Files.isSymbolicLink(file)) throw new IOException("Non-regular blueprint file: " + file);
                    Path relative = source.relativize(file);
                    if (IDENTITY_FILES.contains(relative)) return FileVisitResult.CONTINUE;
                    listener.copying(file);
                    Files.copy(file, output.resolve(relative), StandardCopyOption.COPY_ATTRIBUTES);
                    return FileVisitResult.CONTINUE;
                }
            });
        } catch (IOException | RuntimeException failure) {
            try {
                deleteTree(destination);
            } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
    }

    /**
     * Replaces the blueprint with the world folder the server saved while converting it, keeping no per-world
     * identity. The converted folder is moved, not copied. The blueprint only changes once the result inspects as
     * one this server loads without converting it again.
     */
    public void adoptConversion(Path convertedWorld, boolean modernServer, int serverDataVersion) throws IOException {
        Path staging = conversionStaging(root);
        Path previous = conversionBackup(root);
        deleteTree(staging);
        try {
            moveTree(convertedWorld, staging);
            for (Path leftover : CONVERSION_LEFTOVERS) Files.deleteIfExists(staging.resolve(leftover));
            WorldBlueprint converted = inspect(staging, environment);
            if (converted.needsConversion(modernServer, serverDataVersion))
                throw new IOException("The server saved " + converted.layout + " data that it would convert again");
        } catch (IOException | RuntimeException failure) {
            try {
                deleteTree(staging);
            } catch (IOException cleanup) { failure.addSuppressed(cleanup); }
            throw failure;
        }
        deleteTree(previous);
        MagmaCore.movePath(root, previous);
        try {
            MagmaCore.movePath(staging, root);
        } catch (IOException failure) {
            try {
                MagmaCore.movePath(previous, root);
            } catch (IOException restore) { failure.addSuppressed(restore); }
            throw failure;
        }
        try {
            deleteTree(previous);
        } catch (IOException leftover) {
            // The conversion succeeded; recoverInterruptedConversion removes the old copy on the next load.
        }
    }

    /** Whether a conversion left its staged replacement or the blueprint it replaced beside this one. */
    public static boolean hasInterruptedConversion(Path root) {
        return Files.exists(conversionStaging(root), LinkOption.NOFOLLOW_LINKS)
                || Files.exists(conversionBackup(root), LinkOption.NOFOLLOW_LINKS);
    }

    /** Restores a blueprint whose conversion stopped between moving it aside and moving its replacement in. */
    public static void recoverInterruptedConversion(Path root) throws IOException {
        Path previous = conversionBackup(root);
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS) && Files.isDirectory(previous, LinkOption.NOFOLLOW_LINKS))
            MagmaCore.movePath(previous, root);
        deleteTree(conversionStaging(root));
        deleteTree(previous);
    }

    private static Path conversionStaging(Path root) {
        return root.resolveSibling("." + root.getFileName() + ".converting");
    }

    private static Path conversionBackup(Path root) {
        return root.resolveSibling("." + root.getFileName() + ".previous");
    }

    // Same-volume moves are renames; across volumes the tree is copied and the source removed.
    private static void moveTree(Path source, Path target) throws IOException {
        try {
            MagmaCore.movePath(source, target);
            return;
        } catch (DirectoryNotEmptyException crossVolume) {
            // Fall through to the copy.
        }
        try (Stream<Path> paths = Files.walk(source)) {
            for (Path path : paths.toList()) {
                Path destination = target.resolve(source.relativize(path).toString());
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) Files.createDirectories(destination);
                else Files.copy(path, destination, StandardCopyOption.COPY_ATTRIBUTES);
            }
        }
        deleteTree(source);
    }

    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS)) return;
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
        }
    }
}
