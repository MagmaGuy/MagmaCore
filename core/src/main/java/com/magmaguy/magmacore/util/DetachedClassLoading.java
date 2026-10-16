package com.magmaguy.magmacore.util;

import org.bukkit.Bukkit;

import java.io.IOException;
import java.io.InputStream;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Defines a copy of a class in a JDK class loader whose parent is the server's.
 *
 * <p>An object the server keeps after a plugin is disabled, such as a world's chunk generator,
 * keeps the class loader of its class alive, and with it every class and static of that plugin
 * copy. A copy defined here belongs to its own small loader instead, so a reloaded plugin can be
 * collected. The class may therefore only reference JDK and Bukkit types: no lambdas, nested
 * classes, enums, or other classes from the plugin jar.</p>
 */
public final class DetachedClassLoading {
    private DetachedClassLoading() {
    }

    /**
     * Writes {@code template}'s bytecode under {@code directory} and loads it from there.
     * The returned class's loader is a {@link URLClassLoader}; close it once the class needs
     * nothing more from {@code directory}.
     */
    public static Class<?> define(Class<?> template, Path directory) throws IOException, ClassNotFoundException {
        String resource = template.getName().replace('.', '/') + ".class";
        byte[] bytecode;
        try (InputStream input = template.getClassLoader().getResourceAsStream(resource)) {
            if (input == null) throw new IOException("Missing " + template.getName() + " bytecode.");
            bytecode = input.readAllBytes();
        }
        Path classFile = directory.resolve(resource);
        Files.createDirectories(classFile.getParent());
        Files.write(classFile, bytecode);

        // A JDK class loader, not one declared in this jar. Paper caches the caller of
        // defineClass from plugin code, and the new plugin keeps the stack it was loaded
        // from, so any class from this jar on that path would keep the old plugin loaded.
        URLClassLoader classLoader = new URLClassLoader(new URL[]{directory.toUri().toURL()}, Bukkit.class.getClassLoader());
        return Class.forName(template.getName(), true, classLoader);
    }
}
