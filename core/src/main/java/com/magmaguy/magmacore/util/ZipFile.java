package com.magmaguy.magmacore.util;

import java.io.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class ZipFile {
    private static final ExtractionLimits DEFAULT_EXTRACTION_LIMITS =
            new ExtractionLimits(
                    100_000,
                    2L * 1024L * 1024L * 1024L,
                    2L * 1024L * 1024L * 1024L,
                    2L * 1024L * 1024L * 1024L,
                    1_000.0);

    private ZipFile() {
    }

    public static boolean zip(File directory, String targetZipPath) {
        if (!directory.exists()) {
            Logger.warn("Failed to zip directory " + directory.getPath() + " because it does not exist!");
            return false;
        }

        try {
            ZipUtility.zip(directory, targetZipPath);
            return true;
        } catch (IOException e) {
            e.printStackTrace();
            return false;
        }
    }

    public static File unzip(File zippedFile, File destinationUnzippedFile) throws IOException {
        return unzip(
                zippedFile,
                destinationUnzippedFile,
                DEFAULT_EXTRACTION_LIMITS);
    }

    static File unzip(
            File zippedFile,
            File destinationUnzippedFile,
            ExtractionLimits limits) throws IOException {
        if (zippedFile == null || !zippedFile.isFile()) {
            throw new IOException("ZIP archive does not exist: " + zippedFile);
        }
        if (zippedFile.length() > limits.maxArchiveBytes()) {
            throw new IOException(
                    "ZIP archive exceeds the compressed size limit.");
        }
        Path destination = destinationUnzippedFile.toPath()
                .toAbsolutePath()
                .normalize();
        Files.createDirectories(destination);
        try (var existing = Files.list(destination)) {
            if (existing.findAny().isPresent()) {
                throw new IOException(
                        "ZIP destination must be empty: " + destination);
            }
        }

        byte[] buffer = new byte[8192];
        // Random-access ZipFile reads the central directory, so it handles all conformant
        // archives — including STORED entries with data descriptors, which the streaming
        // ZipInputStream rejects with "only DEFLATED entries can have EXT descriptor".
        try (java.util.zip.ZipFile zipFile = new java.util.zip.ZipFile(zippedFile)) {
            validateArchive(zipFile, destinationUnzippedFile, limits);
            long totalWritten = 0L;
            Enumeration<? extends ZipEntry> entries = zipFile.entries();
            while (entries.hasMoreElements()) {
                ZipEntry zipEntry = entries.nextElement();
                File newFile = newFile(destinationUnzippedFile, zipEntry);
                // Check if directory - isDirectory() only checks for trailing '/', but Windows zips may use '\'
                String entryName = zipEntry.getName();
                boolean isDirectory = zipEntry.isDirectory() || entryName.endsWith("\\") || entryName.endsWith("/");
                if (isDirectory) {
                    if (!newFile.isDirectory() && !newFile.mkdirs()) {
                        throw new IOException("Failed to create directory " + newFile);
                    }
                } else {
                    // Fix for Windows-created archives
                    File parent = newFile.getParentFile();
                    if (!parent.isDirectory() && !parent.mkdirs()) {
                        throw new IOException("Failed to create directory " + parent);
                    }

                    // Write file content
                    try (InputStream in = zipFile.getInputStream(zipEntry);
                         FileOutputStream fileOutputStream = new FileOutputStream(newFile)) {
                        long entryWritten = 0L;
                        int len;
                        while ((len = in.read(buffer)) > 0) {
                            if (entryWritten + len >
                                    limits.maxEntryExpandedBytes()) {
                                throw new IOException(
                                        "ZIP entry exceeds expanded size limit: "
                                                + zipEntry.getName());
                            }
                            if (totalWritten + len >
                                    limits.maxTotalExpandedBytes()) {
                                throw new IOException(
                                        "ZIP archive exceeds total expanded size limit.");
                            }
                            fileOutputStream.write(buffer, 0, len);
                            entryWritten += len;
                            totalWritten += len;
                        }
                    }
                }
                long entryTime = zipEntry.getTime();
                if (entryTime >= 0) newFile.setLastModified(entryTime);
            }
        } catch (IOException failure) {
            deleteExtractedContents(destination);
            throw failure;
        }
        return destinationUnzippedFile;
    }

    private static void validateArchive(
            java.util.zip.ZipFile zipFile,
            File destination,
            ExtractionLimits limits) throws IOException {
        Enumeration<? extends ZipEntry> entries = zipFile.entries();
        Set<String> names = new HashSet<>();
        Set<String> files = new HashSet<>();
        long declaredExpandedBytes = 0L;
        int entryCount = 0;
        while (entries.hasMoreElements()) {
            ZipEntry entry = entries.nextElement();
            entryCount++;
            if (entryCount > limits.maxEntries()) {
                throw new IOException("ZIP archive exceeds entry-count limit.");
            }
            newFile(destination, entry);
            String normalized = normalizeEntryName(entry.getName());
            if (normalized.isEmpty()) continue;
            String key = normalized.toLowerCase(Locale.ROOT);
            if (!names.add(key)) {
                throw new IOException(
                        "ZIP archive contains a duplicate path: "
                                + entry.getName());
            }
            boolean directory = entry.isDirectory() ||
                    entry.getName().endsWith("/") ||
                    entry.getName().endsWith("\\");
            for (String existingFile : files) {
                if (key.startsWith(existingFile + "/") ||
                        (!directory &&
                                existingFile.startsWith(key + "/"))) {
                    throw new IOException(
                            "ZIP archive contains a file/directory path conflict: "
                                    + entry.getName());
                }
            }
            if (!directory) files.add(key);

            long size = entry.getSize();
            if (size >= 0L) {
                if (size > limits.maxEntryExpandedBytes()) {
                    throw new IOException(
                            "ZIP entry exceeds expanded size limit: "
                                    + entry.getName());
                }
                if (declaredExpandedBytes >
                        limits.maxTotalExpandedBytes() - size) {
                    throw new IOException(
                            "ZIP archive exceeds total expanded size limit.");
                }
                declaredExpandedBytes += size;
                long compressed = entry.getCompressedSize();
                if (size > 0L && compressed >= 0L) {
                    double ratio = (double) size /
                            Math.max(1L, compressed);
                    if (ratio > limits.maxCompressionRatio()) {
                        throw new IOException(
                                "ZIP entry exceeds compression-ratio limit: "
                                        + entry.getName());
                    }
                }
            }
        }
    }

    private static String normalizeEntryName(String entryName) {
        String normalized = entryName.replace('\\', '/');
        while (normalized.endsWith("/")) {
            normalized = normalized.substring(
                    0, normalized.length() - 1);
        }
        return normalized;
    }

    private static void deleteExtractedContents(Path destination)
            throws IOException {
        if (!Files.isDirectory(destination)) return;
        try (var children = Files.list(destination)) {
            for (Path child : children.toList()) {
                try (var paths = Files.walk(child)) {
                    for (Path path : paths
                            .sorted(Comparator.reverseOrder())
                            .toList()) {
                        Files.deleteIfExists(path);
                    }
                }
            }
        }
    }

    record ExtractionLimits(
            int maxEntries,
            long maxArchiveBytes,
            long maxEntryExpandedBytes,
            long maxTotalExpandedBytes,
            double maxCompressionRatio) {
        ExtractionLimits {
            if (maxEntries <= 0 || maxArchiveBytes <= 0L ||
                    maxEntryExpandedBytes <= 0L ||
                    maxTotalExpandedBytes <= 0L ||
                    maxCompressionRatio <= 0.0) {
                throw new IllegalArgumentException(
                        "All ZIP extraction limits must be positive.");
            }
        }
    }

    private static File newFile(File destinationDir, ZipEntry zipEntry) throws IOException {
        // Normalize path separators and remove trailing slashes for proper File creation
        String entryName = zipEntry.getName().replace('\\', '/');
        if (entryName.endsWith("/")) {
            entryName = entryName.substring(0, entryName.length() - 1);
        }
        File destFile = new File(destinationDir, entryName);

        String destDirPath = destinationDir.getCanonicalPath();
        String destFilePath = destFile.getCanonicalPath();

        // A root entry ("/" or "") normalizes to an empty name and resolves to the destination
        // itself. That is the archive's own root, not a traversal attempt, so accept it instead
        // of aborting the whole archive - some packs ship one.
        if (destFilePath.equals(destDirPath)) {
            return destFile;
        }

        if (!destFilePath.startsWith(destDirPath + File.separatorChar)) {
            throw new IOException("Entry is outside of the target dir: " + zipEntry.getName());
        }

        return destFile;
    }

    public static class ZipUtility {
        /**
         * A constant for buffer size used to read/write data
         */
        private static final int BUFFER_SIZE = 4096;

        /**
         * Compresses a list of files to a destination zip file
         *
         * @param file        File to zip
         * @param destZipFile The path of the destination zip file
         * @throws FileNotFoundException
         * @throws IOException
         */
        public static void zip(File file, String destZipFile) throws FileNotFoundException, IOException {
            try (FileOutputStream fileOutputStream = new FileOutputStream(destZipFile);
                 ZipOutputStream zos = new ZipOutputStream(fileOutputStream)) {
                // Zip a directory's contents rather than wrapping them in the directory itself.
                if (file.isDirectory()) {
                    File[] children = file.listFiles();
                    if (children == null) throw new IOException("Failed to list directory " + file);
                    for (File child : children) {
                        if (child.isDirectory())
                            zipDirectory(child, child.getName(), zos);
                        else
                            zipFile(child, zos);
                    }
                } else {
                    zipFile(file, zos);
                }
            }
        }

        /**
         * Adds a directory to the current zip output stream
         *
         * @param folder       the directory to be added
         * @param parentFolder the path of parent directory
         * @param zos          the current zip output stream
         * @throws FileNotFoundException
         * @throws IOException
         */
        private static void zipDirectory(File folder, String parentFolder, ZipOutputStream zos) throws FileNotFoundException, IOException {
            File[] children = folder.listFiles();
            if (children == null) throw new IOException("Failed to list directory " + folder);
            for (File file : children) {
                if (file.isDirectory()) {
                    zipDirectory(file, parentFolder + "/" + file.getName(), zos);
                    continue;
                }
                ZipEntry zipEntry = new ZipEntry(parentFolder + "/" + file.getName());
                zippedySplit(zos, file, zipEntry);
            }
        }

        /**
         * Adds a file to the current zip output stream
         *
         * @param file the file to be added
         * @param zos  the current zip output stream
         * @throws FileNotFoundException
         * @throws IOException
         */
        private static void zipFile(File file, ZipOutputStream zos) throws FileNotFoundException, IOException {
            if (file.getName().endsWith(".zip")) return;
            ZipEntry zipEntry = new ZipEntry(file.getName());
            zippedySplit(zos, file, zipEntry);
        }

        private static void zippedySplit(ZipOutputStream zos, File file, ZipEntry zipEntry) throws IOException {
            zipEntry.setTime(0L);
            zos.putNextEntry(zipEntry);
            try (BufferedInputStream bis = new BufferedInputStream(new FileInputStream(file))) {
                byte[] bytesIn = new byte[BUFFER_SIZE];
                int read;
                while ((read = bis.read(bytesIn)) != -1) {
                    zos.write(bytesIn, 0, read);
                }
            } finally {
                zos.closeEntry();
            }
        }
    }
}
