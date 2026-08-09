package com.magmaguy.magmacore.nightbreak;

import org.bukkit.plugin.InvalidDescriptionException;
import org.bukkit.plugin.PluginDescriptionFile;

import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.Collection;
import java.util.HashSet;
import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.CRC32;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;

/**
 * Validates a downloaded Bukkit plugin before it can enter the server update
 * directory.
 */
final class DownloadedPluginJarValidator {
    private static final long MAX_ARCHIVE_BYTES =
            256L * 1024L * 1024L;
    private static final int MAX_ENTRIES = 100_000;
    private static final long MAX_ENTRY_BYTES =
            128L * 1024L * 1024L;
    private static final long MAX_TOTAL_EXPANDED_BYTES =
            1024L * 1024L * 1024L;
    private static final int MAX_DESCRIPTOR_BYTES = 1024 * 1024;
    private static final double MAX_COMPRESSION_RATIO = 1_000.0;
    private static final Set<String> RSPM_DESCRIPTORS = Set.of(
            "plugin.yml",
            "bungee.yml",
            "extension.yml",
            "velocity-plugin.json");

    private DownloadedPluginJarValidator() {
    }

    static ValidationResult validate(File file,
                                     Collection<String> expectedPluginNames,
                                     String expectedVersion) {
        if (file == null || !file.isFile()) {
            return ValidationResult.failure("The downloaded update is not a regular file.");
        }
        if (file.length() > MAX_ARCHIVE_BYTES) {
            return ValidationResult.failure(
                    "The downloaded update exceeds the maximum JAR size.");
        }

        Set<String> normalizedExpectedNames = new HashSet<>();
        if (expectedPluginNames != null) {
            for (String expectedName : expectedPluginNames) {
                if (expectedName != null && !expectedName.isBlank()) {
                    normalizedExpectedNames.add(
                            normalizePluginName(expectedName));
                }
            }
        }
        if (normalizedExpectedNames.isEmpty()) {
            return ValidationResult.failure(
                    "No expected plugin identity was available for validation.");
        }

        try (JarFile jar = new JarFile(file, true)) {
            JarEntry descriptorEntry = null;
            int descriptorCount = 0;
            int entryCount = 0;
            long totalExpandedBytes = 0L;
            Set<String> normalizedEntryNames = new HashSet<>();
            Map<String, JarEntry> universalDescriptors = new HashMap<>();
            var entries = jar.entries();
            byte[] buffer = new byte[8192];
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                entryCount++;
                if (entryCount > MAX_ENTRIES) {
                    return ValidationResult.failure(
                            "The downloaded update contains too many JAR entries.");
                }
                String normalizedEntryName =
                        entry.getName().toLowerCase(Locale.ROOT);
                if (!normalizedEntryNames.add(normalizedEntryName)) {
                    return ValidationResult.failure(
                            "The downloaded update contains a duplicate JAR path.");
                }
                if ("plugin.yml".equals(entry.getName())) {
                    descriptorEntry = entry;
                    descriptorCount++;
                }
                if (RSPM_DESCRIPTORS.contains(entry.getName())) {
                    universalDescriptors.put(entry.getName(), entry);
                }
                if (entry.isDirectory()) continue;
                long declaredSize = entry.getSize();
                if (declaredSize > MAX_ENTRY_BYTES) {
                    return ValidationResult.failure(
                            "The downloaded update contains an oversized JAR entry.");
                }
                long compressedSize = entry.getCompressedSize();
                if (declaredSize > 0L && compressedSize >= 0L &&
                        (double) declaredSize /
                                Math.max(1L, compressedSize) >
                                MAX_COMPRESSION_RATIO) {
                    return ValidationResult.failure(
                            "The downloaded update contains a suspiciously compressed JAR entry.");
                }
                CRC32 crc = new CRC32();
                long bytesRead = 0L;
                try (InputStream input = jar.getInputStream(entry)) {
                    int read;
                    while ((read = input.read(buffer)) != -1) {
                        if (bytesRead + read > MAX_ENTRY_BYTES ||
                                totalExpandedBytes + read >
                                        MAX_TOTAL_EXPANDED_BYTES) {
                            return ValidationResult.failure(
                                    "The downloaded update exceeds expanded JAR size limits.");
                        }
                        crc.update(buffer, 0, read);
                        bytesRead += read;
                        totalExpandedBytes += read;
                    }
                }
                if ((entry.getSize() >= 0L && entry.getSize() != bytesRead) ||
                        (entry.getCrc() >= 0L && entry.getCrc() != crc.getValue())) {
                    return ValidationResult.failure(
                            "The downloaded update contains a damaged JAR entry.");
                }
            }
            if (descriptorCount != 1 || descriptorEntry == null) {
                return ValidationResult.failure(
                        "The downloaded JAR must contain exactly one root plugin.yml.");
            }

            PluginDescriptionFile descriptor;
            try (InputStream input = jar.getInputStream(descriptorEntry)) {
                descriptor = new PluginDescriptionFile(input);
            } catch (InvalidDescriptionException | RuntimeException exception) {
                return ValidationResult.failure(
                        "The downloaded plugin.yml is invalid: " +
                                exception.getMessage());
            }

            String pluginName = descriptor.getName().trim();
            if (!normalizedExpectedNames.contains(
                    normalizePluginName(pluginName))) {
                return ValidationResult.failure(
                        "Downloaded plugin identity '" + pluginName +
                                "' does not match the requested plugin.");
            }

            String pluginVersion = descriptor.getVersion().trim();
            if (pluginVersion.isBlank() ||
                    !normalizeArtifactVersion(pluginVersion).equals(
                            normalizeArtifactVersion(expectedVersion))) {
                return ValidationResult.failure(
                        "Downloaded plugin version '" + pluginVersion +
                                "' does not match expected version '" +
                                expectedVersion + "'.");
            }

            String mainClass = descriptor.getMain().trim();
            if (mainClass.isBlank()) {
                return ValidationResult.failure(
                        "The downloaded plugin descriptor has no main class.");
            }
            String mainClassEntry = mainClass.replace('.', '/') + ".class";
            JarEntry mainEntry = jar.getJarEntry(mainClassEntry);
            if (mainEntry == null || mainEntry.isDirectory()) {
                return ValidationResult.failure(
                        "The downloaded plugin is missing its declared main class.");
            }
            try (InputStream input = jar.getInputStream(mainEntry)) {
                ValidationResult bytecodeFailure =
                        validateClassMagic(input, "plugin");
                if (bytecodeFailure != null) return bytecodeFailure;
            }

            if ("resourcepackmanager".equals(
                    normalizePluginName(pluginName))) {
                ValidationResult universalFailure =
                        validateRspmUniversalDescriptors(
                                jar,
                                universalDescriptors,
                                expectedVersion);
                if (universalFailure != null) {
                    return universalFailure;
                }
            }

            return new ValidationResult(
                    true, null, pluginName, pluginVersion, mainClass);
        } catch (IOException | RuntimeException exception) {
            String message = exception.getMessage();
            if (message == null || message.isBlank()) {
                message = exception.getClass().getSimpleName();
            }
            return ValidationResult.failure(
                    "The downloaded update is not a valid plugin JAR: " + message);
        }
    }

    private static ValidationResult validateRspmUniversalDescriptors(
            JarFile jar,
            Map<String, JarEntry> descriptors,
            String expectedVersion) throws IOException {
        if (!descriptors.keySet().equals(RSPM_DESCRIPTORS)) {
            return ValidationResult.failure(
                    "ResourcePackManager update is missing a universal platform descriptor.");
        }

        DescriptorValues bungee = yamlDescriptor(
                jar, descriptors.get("bungee.yml"));
        DescriptorValues geyser = yamlDescriptor(
                jar, descriptors.get("extension.yml"));
        DescriptorValues velocity = jsonDescriptor(
                jar, descriptors.get("velocity-plugin.json"));
        if (bungee == null || geyser == null || velocity == null) {
            return ValidationResult.failure(
                    "ResourcePackManager has an unreadable universal platform descriptor.");
        }

        if (!"resourcepackmanager".equals(
                normalizePluginName(bungee.name())) ||
                !"resourcepackmanagergeyserbridge".equals(
                        normalizePluginName(geyser.name())) ||
                !"resourcepackmanager".equals(
                        normalizePluginName(velocity.name()))) {
            return ValidationResult.failure(
                    "ResourcePackManager universal platform identities disagree.");
        }
        String normalizedExpected = normalizeArtifactVersion(expectedVersion);
        if (!normalizedExpected.equals(
                        normalizeArtifactVersion(bungee.version())) ||
                !normalizedExpected.equals(
                        normalizeArtifactVersion(geyser.version())) ||
                !normalizedExpected.equals(
                        normalizeArtifactVersion(velocity.version()))) {
            return ValidationResult.failure(
                    "ResourcePackManager universal platform versions disagree.");
        }
        for (DescriptorValues descriptor :
                Set.of(bungee, geyser, velocity)) {
            if (descriptor.mainClass() == null ||
                    descriptor.mainClass().isBlank()) {
                return ValidationResult.failure(
                        "ResourcePackManager universal descriptor has no entrypoint.");
            }
            JarEntry entry = jar.getJarEntry(
                    descriptor.mainClass().replace('.', '/') + ".class");
            if (entry == null || entry.isDirectory()) {
                return ValidationResult.failure(
                        "ResourcePackManager universal descriptor entrypoint is missing.");
            }
            try (InputStream input = jar.getInputStream(entry)) {
                ValidationResult bytecodeFailure =
                        validateClassMagic(input, "universal platform");
                if (bytecodeFailure != null) return bytecodeFailure;
            }
        }
        return null;
    }

    private static DescriptorValues yamlDescriptor(
            JarFile jar, JarEntry entry) throws IOException {
        String source = readDescriptor(jar, entry);
        String name = yamlField(source, "name");
        String version = yamlField(source, "version");
        String main = yamlField(source, "main");
        if (name == null || version == null || main == null) return null;
        return new DescriptorValues(name, version, main);
    }

    private static DescriptorValues jsonDescriptor(
            JarFile jar, JarEntry entry) throws IOException {
        String source = readDescriptor(jar, entry);
        String name = jsonField(source, "name");
        String version = jsonField(source, "version");
        String main = jsonField(source, "main");
        if (name == null || version == null || main == null) return null;
        return new DescriptorValues(name, version, main);
    }

    private static String readDescriptor(
            JarFile jar, JarEntry entry) throws IOException {
        if (entry == null || entry.isDirectory()) {
            throw new IOException("Platform descriptor is missing.");
        }
        if (entry.getSize() > MAX_DESCRIPTOR_BYTES) {
            throw new IOException("Platform descriptor exceeds the size limit.");
        }
        try (InputStream input = jar.getInputStream(entry)) {
            byte[] bytes = input.readNBytes(MAX_DESCRIPTOR_BYTES + 1);
            if (bytes.length > MAX_DESCRIPTOR_BYTES) {
                throw new IOException(
                        "Platform descriptor exceeds the size limit.");
            }
            return new String(bytes, StandardCharsets.UTF_8);
        }
    }

    private static String yamlField(String source, String field) {
        Matcher matcher = Pattern.compile(
                "(?m)^\\s*" + Pattern.quote(field) +
                        "\\s*:\\s*['\\\"]?([^'\\\"#\\r\\n]+)")
                .matcher(source);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static String jsonField(String source, String field) {
        Matcher matcher = Pattern.compile(
                "\\\"" + Pattern.quote(field) +
                        "\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"")
                .matcher(source);
        return matcher.find() ? matcher.group(1).trim() : null;
    }

    private static ValidationResult validateClassMagic(
            InputStream input, String label) throws IOException {
        byte[] magic = input.readNBytes(4);
        if (magic.length == 4 &&
                magic[0] == (byte) 0xCA &&
                magic[1] == (byte) 0xFE &&
                magic[2] == (byte) 0xBA &&
                magic[3] == (byte) 0xBE) {
            return null;
        }
        return ValidationResult.failure(
                "The downloaded " + label +
                        " main class is not valid JVM bytecode.");
    }

    private static String normalizePluginName(String value) {
        return value.trim()
                .replace(' ', '_')
                .toLowerCase(Locale.ROOT);
    }

    private static String normalizeArtifactVersion(String value) {
        if (value == null) return "";
        String normalized = value.trim();
        if (normalized.startsWith("v") || normalized.startsWith("V")) {
            normalized = normalized.substring(1);
        }
        return normalized;
    }

    record ValidationResult(boolean valid,
                            String detail,
                            String pluginName,
                            String pluginVersion,
                            String mainClass) {
        private static ValidationResult failure(String detail) {
            return new ValidationResult(false, detail, null, null, null);
        }
    }

    private record DescriptorValues(
            String name, String version, String mainClass) {
    }
}
