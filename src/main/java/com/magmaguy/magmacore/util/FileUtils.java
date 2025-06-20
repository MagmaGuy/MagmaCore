package com.magmaguy.magmacore.util;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Utility methods for file operations, without external dependencies.
 */
public class FileUtils {

    private FileUtils() {
    }

    /**
     * Recursively deletes a directory and all its contents.
     *
     * @param directory the root directory to delete
     * @throws IOException if an I/O error occurs
     */
    public static void deleteDirectory(Path directory) throws IOException {
        if (directory == null || !Files.exists(directory)) {
            return;
        }

        // Walk the file tree and delete files/directories post-order
        Files.walkFileTree(directory, new SimpleFileVisitor<Path>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc)
                    throws IOException {
                if (exc != null) {
                    // Directory iteration failed; rethrow
                    throw exc;
                }
                Files.delete(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    /**
     * Convenience method accepting java.io.File.
     *
     * @param directory the directory to delete
     * @throws IOException if an I/O error occurs
     */
    public static void deleteDirectory(java.io.File directory) throws IOException {
        if (directory == null) {
            return;
        }
        deleteDirectory(directory.toPath());
    }
}
