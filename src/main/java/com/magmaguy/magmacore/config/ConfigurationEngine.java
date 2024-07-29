package com.magmaguy.magmacore.config;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.util.ChatColorConverter;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;

import java.io.File;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

public class ConfigurationEngine {
    public static File fileCreator(String path, String fileName) {
        File file = new File(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getPath() + "/" + path + "/", fileName);
        return fileCreator(file);
    }

    public static File fileCreator(String fileName) {
        File file = new File(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getPath(), fileName);
        return fileCreator(file);
    }

    public static File fileCreator(File file) {
        if (!file.exists())
            try {
                file.getParentFile().mkdirs();
                file.createNewFile();
            } catch (IOException ex) {
                Logger.warn("Error generating the plugin file: " + file.getName());
            }
        return file;
    }

    public static FileConfiguration fileConfigurationCreator(File file) {
        try {
            return YamlConfiguration.loadConfiguration(new InputStreamReader(Files.newInputStream(file.toPath()), StandardCharsets.UTF_8));
        } catch (Exception exception) {
            Logger.warn("Failed to read configuration from file " + file.getName());
            return null;
        }
    }

    public static void fileSaverCustomValues(FileConfiguration fileConfiguration, File file) {
        fileConfiguration.options().copyDefaults(true);

        try {
            fileConfiguration.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static void fileSaverOnlyDefaults(FileConfiguration fileConfiguration, File file) {
        fileConfiguration.options().copyDefaults(true);
        UnusedNodeHandler.clearNodes(fileConfiguration);

        try {
            fileConfiguration.save(file);
        } catch (IOException e) {
            e.printStackTrace();
        }

    }

    public static void setComments(FileConfiguration fileConfiguration, String key, List<String> comments) {
        fileConfiguration.setComments(key, comments);
    }

    public static Boolean setBoolean(FileConfiguration fileConfiguration, String key, boolean defaultValue) {
        try {
            fileConfiguration.addDefault(key, defaultValue);
        } catch (Exception e) {
            Logger.warn("Attempted to write key " + key + " with value " + defaultValue + " to " + fileConfiguration.getName() + " and that contained an illegal argument!");
            e.printStackTrace();
        }
        return fileConfiguration.getBoolean(key);
    }

    public static Boolean setBoolean(List<String> comments, FileConfiguration fileConfiguration, String key, boolean defaultValue) {
        boolean value = setBoolean(fileConfiguration, key, defaultValue);
        setComments(fileConfiguration, key, comments);
        return value;
    }

    public static String setString(FileConfiguration fileConfiguration, String key, String defaultValue) {
        fileConfiguration.addDefault(key, defaultValue);
        return ChatColorConverter.convert(fileConfiguration.getString(key));
    }

    public static String setString(List<String> comments, FileConfiguration fileConfiguration, String key, String defaultValue) {
        String value = setString(fileConfiguration, key, defaultValue);
        setComments(fileConfiguration, key, comments);
        return value;
    }


    public static int setInt(FileConfiguration fileConfiguration, String key, int defaultValue) {
        fileConfiguration.addDefault(key, defaultValue);
        return fileConfiguration.getInt(key);
    }

    public static int setInt(List<String> comments, FileConfiguration fileConfiguration, String key, int defaultValue) {
        int value = setInt(fileConfiguration, key, defaultValue);
        setComments(fileConfiguration, key, comments);
        return value;
    }

    public static double setDouble(FileConfiguration fileConfiguration, String key, double defaultValue) {
        fileConfiguration.addDefault(key, defaultValue);
        return fileConfiguration.getDouble(key);
    }

    public static double setDouble(List<String> comments, FileConfiguration fileConfiguration, String key, double defaultValue) {
        double value = setDouble(fileConfiguration, key, defaultValue);
        setComments(fileConfiguration, key, comments);
        return value;
    }

    public static List setList(FileConfiguration fileConfiguration, String key, List defaultValue) {
        fileConfiguration.addDefault(key, defaultValue);
        return fileConfiguration.getList(key);
    }

    public static List setList(List<String> comment, FileConfiguration fileConfiguration, String key, List defaultValue) {
        List value = setList(fileConfiguration, key, defaultValue);
        setComments(fileConfiguration, key, comment);
        return value;
    }

    public static ItemStack setItemStack(FileConfiguration fileConfiguration, String key, ItemStack itemStack) {
        fileConfiguration.addDefault(key + ".material", itemStack.getType().toString());
        if (itemStack.hasItemMeta() && itemStack.getItemMeta().hasDisplayName())
            fileConfiguration.addDefault(key + ".name", itemStack.getItemMeta().getDisplayName());
        if (itemStack.hasItemMeta() && itemStack.getItemMeta().hasLore())
            fileConfiguration.addDefault(key + ".lore", itemStack.getItemMeta().getLore());
        if (itemStack.getType().equals(Material.PLAYER_HEAD))
            fileConfiguration.addDefault(key + ".owner", ((SkullMeta) itemStack.getItemMeta()).getOwner());
        Material material;
        try {
            material = Material.valueOf(fileConfiguration.getString(key + ".material"));
        } catch (Exception ex) {
            Logger.warn("Material type " + fileConfiguration.getString(key + ".material") + " is not valid! Correct it to make a valid item.");
            return null;
        }
        String name = "";
        try {
            name = fileConfiguration.getString(key + ".name");
        } catch (Exception ex) {
            Logger.warn("Item name " + fileConfiguration.getString(key + ".name") + " is not valid! Correct it to make a valid item.");
        }
        List<String> lore = new ArrayList<>();
        try {
            lore = fileConfiguration.getStringList(key + ".lore");
        } catch (Exception ex) {
            Logger.warn("Item lore " + fileConfiguration.getString(key + ".lore") + " is not valid! Correct it to make a valid item.");
        }
        ItemStack fileItemStack = ItemStackGenerator.generateItemStack(material, name, lore);
        if (material == Material.PLAYER_HEAD)
            ((SkullMeta) itemStack.getItemMeta()).setOwningPlayer(Bukkit.getOfflinePlayer(fileConfiguration.getString(key + ".owner")));
        return fileItemStack;
    }

    public static boolean writeValue(Object value, File file, FileConfiguration fileConfiguration, String path) {
        fileConfiguration.set(path, value);
        try {
            fileSaverCustomValues(fileConfiguration, file);
        } catch (Exception exception) {
            Logger.warn("Failed to write value for " + path + " in file " + file.getName());
            return false;
        }
        return true;
    }

    public static void removeValue(File file, FileConfiguration fileConfiguration, String path) {
        writeValue(null, file, fileConfiguration, path);
    }
}
