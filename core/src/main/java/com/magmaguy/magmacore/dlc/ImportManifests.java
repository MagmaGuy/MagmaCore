package com.magmaguy.magmacore.dlc;

import com.google.gson.Gson;
import com.google.gson.JsonSyntaxException;
import com.magmaguy.magmacore.util.Logger;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.*;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * Remembers which files each imported package installed, keyed by the archive name without its
 * version suffix ({@code the-vampire-manor_v22.zip} becomes {@code the-vampire-manor}). Imports only
 * copy and overwrite, so without this record a file that a newer package version stops shipping would
 * stay installed and keep loading forever.
 */
final class ImportManifests {
    static final String DIRECTORY = "import manifests";
    private static final int SCHEMA_VERSION = 1;
    private static final Pattern VERSION_SUFFIX = Pattern.compile("(?i)[ _-]v\\d+$");
    private static final Gson GSON = new Gson();

    private final Path pluginsDirectory;
    private final Path manifestDirectory;

    ImportManifests(Path pluginsDirectory) {
        this.pluginsDirectory = pluginsDirectory.toAbsolutePath().normalize();
        this.manifestDirectory = this.pluginsDirectory.resolve("MagmaCore").resolve(DIRECTORY);
    }

    /**
     * @return the version-independent package identity, or null when the archive name has none
     */
    static String packageKey(String archiveName) {
        if (archiveName == null) return null;
        String name = archiveName.trim();
        if (name.toLowerCase(Locale.ROOT).endsWith(".zip")) name = name.substring(0, name.length() - ".zip".length());
        name = VERSION_SUFFIX.matcher(name).replaceFirst("");
        name = name.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9._-]", "_");
        return name.isBlank() || name.startsWith(".") ? null : name;
    }

    /**
     * @return the installed file's path relative to the plugins folder, or null for paths outside it
     * such as world folders, which imports replace wholesale
     */
    String relativize(Path installedFile) {
        Path normalized = installedFile.toAbsolutePath().normalize();
        if (!normalized.startsWith(pluginsDirectory) || normalized.equals(pluginsDirectory)) return null;
        Path relative = pluginsDirectory.relativize(normalized);
        if (relative.getName(0).toString().equalsIgnoreCase("MagmaCore")) return null;
        return relative.toString().replace('\\', '/');
    }

    Path resolve(String relativePath) throws IOException {
        Path resolved = pluginsDirectory.resolve(relativePath).normalize();
        if (!resolved.startsWith(pluginsDirectory) || relativize(resolved) == null)
            throw new IOException("Import manifest entry escapes the plugins folder: " + relativePath);
        return resolved;
    }

    /**
     * Files the previous import of this package installed that the current import no longer ships.
     * Files another package still lists are kept, because that package needs them. When the previous
     * and current file lists share nothing, the archive name was reused by an unrelated package, so
     * nothing is reported.
     */
    List<String> droppedFiles(String key, Collection<String> installed) throws IOException {
        Set<String> previous = load(key);
        if (previous == null || previous.isEmpty()) return List.of();
        Set<String> current = lowerCase(installed);
        if (Collections.disjoint(lowerCase(previous), current)) {
            Logger.info("Import " + key + " shares no files with the previous import under that name;"
                    + " leaving the previously installed files in place.");
            return List.of();
        }
        Set<String> otherPackages = otherPackagesFiles(key);
        List<String> dropped = new ArrayList<>();
        for (String path : previous) {
            String lower = path.toLowerCase(Locale.ROOT);
            // Case-insensitive so a renamed-case file on Windows is never mistaken for a removed one.
            if (current.contains(lower) || otherPackages.contains(lower)) continue;
            Path file = resolve(path);
            if (Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) dropped.add(path);
        }
        Collections.sort(dropped);
        return dropped;
    }

    /**
     * Where a dropped file goes: the same {@code MagmaCore/outdated files/<plugin>/<batch>/} layout used
     * for retired configuration files, so administrators find both in one place.
     */
    Path archiveDestination(String relativePath, String batchName) throws IOException {
        Path source = resolve(relativePath);
        Path relative = pluginsDirectory.relativize(source);
        Path archived = pluginsDirectory.resolve("MagmaCore")
                .resolve(com.magmaguy.magmacore.config.OutdatedConfigurationArchive.DIRECTORY)
                .resolve(relative.getName(0).toString())
                .resolve(batchName);
        if (relative.getNameCount() > 1) archived = archived.resolve(relative.subpath(1, relative.getNameCount()));
        return archived.normalize();
    }

    static String batchName(String key) {
        return Instant.now().toString().replace(':', '-') + "-" + key;
    }

    void save(String key, String archiveName, Collection<String> installed) throws IOException {
        Files.createDirectories(manifestDirectory);
        Manifest manifest = new Manifest();
        manifest.schemaVersion = SCHEMA_VERSION;
        manifest.packageKey = key;
        manifest.archive = archiveName;
        manifest.importedAt = Instant.now().toString();
        manifest.files = new ArrayList<>(new TreeSet<>(installed));
        Path destination = manifestPath(key);
        Path temporary = Files.createTempFile(manifestDirectory, "." + key + ".", ".tmp");
        try {
            Files.writeString(temporary, GSON.toJson(manifest), StandardCharsets.UTF_8);
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException exception) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private Set<String> load(String key) throws IOException {
        return read(manifestPath(key));
    }

    private Set<String> otherPackagesFiles(String key) throws IOException {
        Set<String> files = new HashSet<>();
        if (!Files.isDirectory(manifestDirectory, LinkOption.NOFOLLOW_LINKS)) return files;
        Path own = manifestPath(key);
        try (Stream<Path> manifests = Files.list(manifestDirectory)) {
            for (Path manifest : manifests.filter(path -> path.getFileName().toString().endsWith(".json")).toList()) {
                if (manifest.equals(own)) continue;
                Set<String> listed = read(manifest);
                if (listed != null) files.addAll(lowerCase(listed));
            }
        }
        return files;
    }

    private Set<String> read(Path manifestFile) throws IOException {
        if (!Files.isRegularFile(manifestFile, LinkOption.NOFOLLOW_LINKS)) return null;
        try (Reader reader = Files.newBufferedReader(manifestFile, StandardCharsets.UTF_8)) {
            Manifest manifest = GSON.fromJson(reader, Manifest.class);
            if (manifest == null || manifest.files == null || manifest.schemaVersion != SCHEMA_VERSION) return null;
            return new LinkedHashSet<>(manifest.files);
        } catch (JsonSyntaxException malformed) {
            // A damaged record only costs this package its cleanup; it must never block the import.
            Logger.warn("Ignoring unreadable import manifest " + manifestFile + ": " + malformed.getMessage());
            return null;
        }
    }

    private Path manifestPath(String key) {
        return manifestDirectory.resolve(key + ".json");
    }

    private static Set<String> lowerCase(Collection<String> paths) {
        Set<String> lower = new HashSet<>();
        for (String path : paths) lower.add(path.toLowerCase(Locale.ROOT));
        return lower;
    }

    private static final class Manifest {
        int schemaVersion;
        String packageKey;
        String archive;
        String importedAt;
        List<String> files;
    }
}
