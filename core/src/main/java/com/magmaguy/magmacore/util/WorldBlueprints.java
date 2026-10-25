package com.magmaguy.magmacore.util;

import com.magmaguy.magmacore.MagmaCore;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.plugin.Plugin;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Queue;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * The world blueprints a plugin copies into new worlds, such as dungeon and level maps. Each blueprint is converted
 * once into the format this server saves worlds in, so its copies load without the server converting every one, and
 * no copy reads a blueprint while its conversion replaces it.
 */
public final class WorldBlueprints {
    private static final Set<Path> pendingConversions = ConcurrentHashMap.newKeySet();
    // Conversions take turns; each owns the conversion world from copying a blueprint into it until it is adopted.
    private static final Queue<Conversion> conversionQueue = new ConcurrentLinkedQueue<>();
    private static final AtomicBoolean converting = new AtomicBoolean();
    // Copies read blueprints; a conversion replaces one under the write lock, off the main thread.
    private static final ReentrantReadWriteLock blueprintLock = new ReentrantReadWriteLock();

    private WorldBlueprints() {
    }

    /** Restores a blueprint an interrupted conversion left half replaced, then checks that this server can copy it. */
    public static void validate(Path root, World.Environment environment) throws IOException {
        recoverInterruptedConversion(root);
        blueprintLock.readLock().lock();
        try {
            WorldBlueprint.inspect(root, environment).requireCompatibleServer(WorldFolderResolver.usesModernStorage());
        } finally {
            blueprintLock.readLock().unlock();
        }
    }

    /**
     * Copies a blueprint into a new world named {@code worldName}, in the folder this server loads that world from.
     *
     * @return the new world folder
     */
    public static Path copy(Path root, World.Environment environment, String worldName,
                            WorldBlueprint.CopyListener listener) throws IOException {
        Path name = Path.of(worldName);
        if (worldName.isBlank() || name.getNameCount() != 1 || !worldName.equals(name.getFileName().toString())
                || worldName.equals(".") || worldName.equals(".."))
            throw new IOException("Invalid world name: " + worldName);
        recoverInterruptedConversion(root);
        blueprintLock.readLock().lock();
        try {
            WorldBlueprint blueprint = WorldBlueprint.inspect(root, environment);
            boolean modern = WorldFolderResolver.usesModernStorage();
            if (Bukkit.getWorld(worldName) != null || WorldFolderResolver.folderExists(worldName))
                throw new IOException("World destination already exists: " + worldName);
            Path destination = blueprint.usesModernDestination(modern)
                    ? WorldFolderResolver.modernFolder(worldName) : WorldFolderResolver.legacyFolder(worldName);
            blueprint.copyTo(destination, environment, modern, listener);
            return destination;
        } finally {
            blueprintLock.readLock().unlock();
        }
    }

    /**
     * Queues the one-time conversion of a blueprint, unless it already loads without converting. The blueprint is
     * copied into a reserved world, the server loads it and saves it on unload in its own format (Paper dimension
     * storage, the Minecraft 26.1+ vanilla layout, or current level data on older servers), and the saved result
     * replaces the blueprint off the main thread. A failed conversion leaves the blueprint as it was, and its copies
     * keep converting as they load. Imports replace blueprints whole, so updated content converts again.
     */
    public static void convert(Path root, World.Environment environment, String name) {
        try {
            recoverInterruptedConversion(root);
            if (!WorldBlueprint.inspect(root, environment)
                    .needsConversion(WorldFolderResolver.usesModernStorage(), Bukkit.getUnsafe().getDataVersion())) return;
        } catch (IOException | RuntimeException failure) {
            return; // validate and copy report blueprints that cannot be inspected
        }
        if (!pendingConversions.add(root)) return;
        Plugin plugin = MagmaCore.getInstance().getRequestingPlugin();
        conversionQueue.add(new Conversion(root, environment, name, plugin));
        if (!schedule(plugin, WorldBlueprints::convertNext, false)) abandonConversions();
    }

    private static void recoverInterruptedConversion(Path root) throws IOException {
        if (!WorldBlueprint.hasInterruptedConversion(root)) return;
        blueprintLock.writeLock().lock();
        try {
            // A conversion only stages its replacement under the write lock, so anything left now was interrupted.
            if (WorldBlueprint.hasInterruptedConversion(root)) WorldBlueprint.recoverInterruptedConversion(root);
        } finally {
            blueprintLock.writeLock().unlock();
        }
    }

    // Main thread.
    private static void convertNext() {
        if (!converting.compareAndSet(false, true)) return;
        Conversion conversion = conversionQueue.poll();
        if (conversion == null) {
            converting.set(false);
            return;
        }
        conversion.start();
    }

    private static boolean schedule(Plugin plugin, Runnable task, boolean async) {
        try {
            if (async) Bukkit.getScheduler().runTaskAsynchronously(plugin, task);
            else Bukkit.getScheduler().runTask(plugin, task);
            return true;
        } catch (RuntimeException disabled) {
            return false;
        }
    }

    // A disabled plugin cannot schedule the next step; its next start queues the conversions again.
    private static void abandonConversions() {
        conversionQueue.clear();
        pendingConversions.clear();
        converting.set(false);
    }

    private static final class Conversion {
        private final Path root;
        private final World.Environment environment;
        private final String name;
        private final Plugin plugin;
        // Reserved for conversions, so anything found under this name is an earlier conversion's leftover.
        private final String worldName;
        private boolean modern;
        private int dataVersion;
        private WorldBlueprint blueprint;
        private Path converted;
        private volatile boolean worldStillLoaded;

        private Conversion(Path root, World.Environment environment, String name, Plugin plugin) {
            this.root = root;
            this.environment = environment;
            this.name = name;
            this.plugin = plugin;
            this.worldName = plugin.getName().toLowerCase(Locale.ROOT) + "_blueprint_conversion";
        }

        // Main thread: reads the server's world format, then copies the blueprint into the conversion world off it.
        private void start() {
            modern = WorldFolderResolver.usesModernStorage();
            dataVersion = Bukkit.getUnsafe().getDataVersion();
            if (Bukkit.getWorld(worldName) != null) {
                worldStillLoaded = true;
                fail(new IOException("the conversion world " + worldName + " is still loaded"));
                return;
            }
            if (!schedule(plugin, this::prepare, true)) finish();
        }

        private void prepare() {
            boolean prepared;
            try {
                WorldFolderResolver.deleteAllLayouts(worldName);
                prepared = copyBlueprint();
            } catch (IOException | RuntimeException failure) {
                fail(failure);
                return;
            }
            if (!prepared || !schedule(plugin, this::load, false)) finish();
        }

        private boolean copyBlueprint() throws IOException {
            blueprintLock.readLock().lock();
            try {
                blueprint = WorldBlueprint.inspect(root, environment);
                // An earlier conversion or an import may have made the blueprint current since it was queued.
                if (!blueprint.needsConversion(modern, dataVersion)) return false;
                blueprint.copyForConversion(WorldFolderResolver.legacyFolder(worldName));
                return true;
            } finally {
                blueprintLock.readLock().unlock();
            }
        }

        // Main thread: the server converts the copy as it loads it and saves it in its own format as it unloads it.
        private void load() {
            try {
                World world = Bukkit.createWorld(new WorldCreator(worldName)
                        .environment(environment).generator(TemporaryWorldManager.voidGenerator()));
                if (world == null) throw new IOException("the server did not load the conversion world");
                if (!Bukkit.unloadWorld(world, true)) {
                    worldStillLoaded = true;
                    throw new IOException("the server did not unload the conversion world");
                }
            } catch (IOException | RuntimeException failure) {
                fail(failure);
                return;
            }
            converted = modern ? WorldFolderResolver.modernFolder(worldName) : WorldFolderResolver.legacyFolder(worldName);
            if (!schedule(plugin, this::adopt, true)) finish();
        }

        private void adopt() {
            try {
                blueprintLock.writeLock().lock();
                try {
                    blueprint.adoptConversion(converted, modern, dataVersion);
                } finally {
                    blueprintLock.writeLock().unlock();
                }
            } catch (IOException | RuntimeException failure) {
                fail(failure);
                return;
            }
            Logger.info("Converted world blueprint " + name + " to this server's world format.");
            finish();
        }

        private void fail(Throwable failure) {
            Logger.warn("Could not convert world blueprint " + name + "; each copy keeps converting it as it loads: "
                    + failure.getMessage());
            finish();
        }

        // Removes the conversion world off the main thread, then lets the next conversion start.
        private void finish() {
            Runnable cleanup = () -> {
                if (!worldStillLoaded) WorldFolderResolver.deleteAllLayouts(worldName);
                pendingConversions.remove(root);
                converting.set(false);
                if (!schedule(plugin, WorldBlueprints::convertNext, false)) abandonConversions();
            };
            if (Bukkit.isPrimaryThread()) {
                if (!schedule(plugin, cleanup, true)) cleanup.run();
            } else {
                cleanup.run();
            }
        }
    }
}
