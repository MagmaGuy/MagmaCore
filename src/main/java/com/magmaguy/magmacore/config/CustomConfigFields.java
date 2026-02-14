package com.magmaguy.magmacore.config;

import com.magmaguy.magmacore.util.*;
import lombok.Getter;
import lombok.Setter;
import org.bukkit.*;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.util.Vector;

import java.io.File;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;

public abstract class CustomConfigFields {

    @Getter
    protected String filename;
    @Getter
    @Setter
    protected boolean isEnabled;
    @Getter
    @Setter
    protected FileConfiguration fileConfiguration;
    @Getter
    @Setter
    protected File file;

    /**
     * Used by plugin-generated files (defaults)
     *
     * @param filename
     * @param isEnabled
     */
    public CustomConfigFields(String filename, boolean isEnabled) {
        this.filename = filename.contains(".yml") ? filename : filename + ".yml";
        this.isEnabled = isEnabled;
    }

    public CompletableFuture<Void> setEnabledAndSave(boolean enabled) {
        this.isEnabled = enabled;
        this.fileConfiguration.set("isEnabled", enabled);
        return CompletableFuture.runAsync(() -> {
            try {
                this.fileConfiguration.save(this.file);
            } catch (IOException e) {
                throw new RuntimeException(e);
            }
        });
    }

    public abstract void processConfigFields();

    protected boolean configHas(String configKey) {
        return fileConfiguration.contains(configKey);
    }

    protected String processString(String path, String value, String pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || !Objects.equals(value, pluginDefault))
                fileConfiguration.addDefault(path, value);
            return value;
        }
        try {
            return ChatColorConverter.convert(fileConfiguration.getString(path));
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return value;
    }


    public List<Object> processList(String path, List<Object> value, List<Object> pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || value != pluginDefault)
                fileConfiguration.addDefault(path, value);
            return value;
        }
        try {
            return new ArrayList<>(Objects.requireNonNull(fileConfiguration.getList(path)));
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return value;
    }

    public List<String> processStringList(String path, List<String> value, List<String> pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || value != pluginDefault)
                fileConfiguration.addDefault(path, value);
            return value;
        }
        try {
            List<String> list = new ArrayList<>();
            for (String string : fileConfiguration.getStringList(path))
                list.add(ChatColorConverter.convert(string));
            return list;
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return value;
    }

    /**
     * This not only gets a list of worlds, but gets a list of already loaded worlds. This might cause issues if the worlds
     * aren't loaded when the code for getting worlds runs.
     *
     * @param path          Configuration path
     * @param pluginDefault Default value - should be null or empty
     * @return Worlds from the list that are loaded at the time this runs, probably on startup
     */
    protected List<World> processWorldList(String path, List<World> value, List<World> pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (value != null && (forceWriteDefault || value != pluginDefault))
                processStringList(path, worldListToStringListConverter(value), worldListToStringListConverter(pluginDefault), forceWriteDefault);
            return value;
        }
        try {
            List<String> validWorldStrings = processStringList(path, worldListToStringListConverter(pluginDefault), worldListToStringListConverter(value), forceWriteDefault);
            List<World> validWorlds = new ArrayList<>();
            if (!validWorldStrings.isEmpty())
                for (String string : validWorldStrings) {
                    World world = Bukkit.getWorld(string);
                    if (world != null)
                        validWorlds.add(world);
                }
            return validWorlds;
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return value;
    }

    private List<String> worldListToStringListConverter(List<World> pluginDefault) {
        if (pluginDefault == null) return null;
        List<String> newList = new ArrayList<>();
        pluginDefault.forEach(element -> newList.add(element.getName()));
        return newList;
    }


    protected <T extends Enum<T>> List<T> processEnumList(String path, List<T> value, List<T> pluginDefault, Class<T> enumClass, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || value != pluginDefault)
                processStringList(path, enumListToStringListConverter(value), enumListToStringListConverter(pluginDefault), forceWriteDefault);
            return value;
        }
        try {
            List<T> newList = new ArrayList<>();
            List<String> stringList = processStringList(path, enumListToStringListConverter(value), enumListToStringListConverter(pluginDefault), forceWriteDefault);
            stringList.forEach(string -> {
                try {
                    newList.add(Enum.valueOf(enumClass, string.toUpperCase(Locale.ROOT)));
                } catch (Exception ex) {
                    // Materials for future versions (e.g. spears) may not exist yet — skip silently
                    if (string != null && string.toUpperCase(Locale.ROOT).endsWith("_SPEAR"))
                        return;
                    Logger.warn(filename + " : " + "Value " + string + " is not a valid for " + path + " ! This may be due to your server version, or due to an invalid value!");
                }
            });
            return newList;
        } catch (
                Exception ex) {
            ex.printStackTrace();
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return value;
    }

    private <T extends Enum<T>> List<String> enumListToStringListConverter(List<T> list) {
        if (list == null) return Collections.emptyList();
        List<String> newList = new ArrayList<>();
        list.forEach(element -> newList.add(element.toString()));
        return newList;
    }

    protected int processInt(String path, int value, int pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || value != pluginDefault) fileConfiguration.addDefault(path, value);
            return value;
        }
        try {
            return fileConfiguration.getInt(path);
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return value;
    }

    protected long processLong(String path, long value, long pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || value != pluginDefault) fileConfiguration.addDefault(path, value);
            return value;
        }
        try {
            return fileConfiguration.getLong(path);
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return value;
    }


    protected double processDouble(String path, double value, double pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || value != pluginDefault) fileConfiguration.addDefault(path, value);
            return value;
        }
        try {
            return fileConfiguration.getDouble(path);
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return value;
    }

    protected Double processDouble(String path, Double value, Double pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || !Objects.equals(value, pluginDefault)) fileConfiguration.addDefault(path, value);
            return value;
        }
        try {
            return fileConfiguration.getDouble(path);
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return value;
    }

    protected boolean processBoolean(String path, boolean value, boolean pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || value != pluginDefault) fileConfiguration.addDefault(path, value);
            return value;
        }
        try {
            return fileConfiguration.getBoolean(path);
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return value;
    }

    public <T extends Enum<T>> T processEnum(String path, T value, T pluginDefault, Class<T> enumClass, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || value != pluginDefault) {
                String valueString = null;
                if (value != null)
                    valueString = value.toString().toUpperCase(Locale.ROOT);
                String pluginDefaultString = null;
                if (pluginDefault != null)
                    pluginDefaultString = pluginDefault.toString().toUpperCase(Locale.ROOT);
                processString(path, valueString, pluginDefaultString, forceWriteDefault);
            }
            return value;
        }
        try {
            String rawValue = fileConfiguration.getString(path).toUpperCase(Locale.ROOT);
            if (!VersionChecker.serverVersionOlderThan(21, 9) && rawValue.equals("CHAIN"))
                return Enum.valueOf(enumClass, "IRON_CHAIN");
            return Enum.valueOf(enumClass, rawValue);
        } catch (Exception ex) {
            String rawValue = fileConfiguration.getString(path);
            // Materials for future versions (e.g. spears) may not exist yet — skip silently
            if (rawValue != null && rawValue.toUpperCase(Locale.ROOT).endsWith("_SPEAR"))
                return pluginDefault;
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + rawValue);
            value = null;
        }
        if (value == null)
            return pluginDefault;
        return value;
    }

    public ItemStack processItemStack(String path, ItemStack value, ItemStack pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || value != pluginDefault)
                processString(path, itemStackDeserializer(value), itemStackDeserializer(pluginDefault), forceWriteDefault);
            return value;
        }
        try {
            String materialString = processString(path, itemStackDeserializer(value), itemStackDeserializer(pluginDefault), forceWriteDefault);
            if (materialString == null)
                return null;
            if (materialString.matches("[0-9a-f]{8}-[0-9a-f]{4}-[1-5][0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")) {
                ItemStack playerHead = new ItemStack(Material.PLAYER_HEAD);
                SkullMeta skullMeta = (SkullMeta) playerHead.getItemMeta();
                skullMeta.setOwningPlayer(Bukkit.getOfflinePlayer(UUID.fromString(materialString)));
                playerHead.setItemMeta(skullMeta);
                return playerHead;
            }
            String baseMaterial = materialString.contains(":") ? materialString.split(":")[0] : materialString;
            Material material = Material.getMaterial(baseMaterial);

            // Materials for future versions (e.g. spears) may not exist yet — skip silently
            if (material == null) {
                if (baseMaterial.endsWith("_SPEAR")) return null;
                throw new IllegalArgumentException("Unknown material: " + baseMaterial);
            }

            if (materialString.contains(":")) {
                ItemStack itemStack = ItemStackGenerator.generateItemStack(material);
                if (baseMaterial.contains("leather_") || baseMaterial.contains("LEATHER_")) {
                    LeatherArmorMeta leatherArmorMeta = (LeatherArmorMeta) itemStack.getItemMeta();
                    leatherArmorMeta.setColor(Color.fromRGB(Integer.parseInt(materialString.split(":")[1], 16)));
                    itemStack.setItemMeta(leatherArmorMeta);
                } else {
                    ItemMeta itemMeta = itemStack.getItemMeta();
                    itemMeta.setCustomModelData(Integer.parseInt(materialString.split(":")[1]));
                    itemStack.setItemMeta(itemMeta);
                }
                return itemStack;
            } else
                return ItemStackGenerator.generateItemStack(material);
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return value;
    }

    public Map<String, Object> processMap(String path, Map<String, Object> value) {
        if (!configHas(path) && value != null)
            fileConfiguration.addDefaults(value);
        if (fileConfiguration.get(path) == null) return Collections.emptyMap();
        return fileConfiguration.getConfigurationSection(path).getValues(false);
    }

    public Map<String, Object> processMapWithKey(String path, Map<String, Object> value) {
        if (!configHas(path) && value != null) {
            fileConfiguration.addDefault(path, value);
            fileConfiguration.createSection(path, value);
        }
        if (fileConfiguration.get(path) == null)
            return Collections.emptyMap();
        return fileConfiguration.getConfigurationSection(path).getValues(false);
    }

    public ConfigurationSection processConfigurationSection(String path, Map<String, Object> value) {
        if (!configHas(path) && value != null)
            fileConfiguration.addDefaults(value);
        ConfigurationSection newValue = fileConfiguration.getConfigurationSection(path);

        return newValue;
    }

    private String itemStackDeserializer(ItemStack itemStack) {
        if (itemStack == null) return null;
        return itemStack.getType().toString();
    }

    protected Location processLocation(String path, Location value, String pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || !Objects.equals(value, pluginDefault))
                fileConfiguration.addDefault(path, ConfigurationLocation.deserialize(value));
            return value;
        }
        try {
            return ConfigurationLocation.serialize(fileConfiguration.getString(path));
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return null;
    }

    protected Vector processVector(String path, Vector value, Vector pluginDefault, boolean forceWriteDefault) {
        if (!configHas(path)) {
            if (forceWriteDefault || !Objects.equals(value, pluginDefault))
                if (pluginDefault != null) {
                    String vectorString = value.getX() + "," + value.getY() + "," + value.getZ();
                    fileConfiguration.addDefault(path, vectorString);
                }
            return value;
        }
        try {
            String string = fileConfiguration.getString(path);
            if (string == null) return null;
            String[] strings = string.split(",");
            if (strings.length < 3) {
                Logger.warn("File " + filename + " has an incorrect entry for " + path);
                return null;
            }
            return new Vector(Double.parseDouble(strings[0]), Double.parseDouble(strings[1]), Double.parseDouble(strings[2]));
        } catch (Exception ex) {
            Logger.warn("File " + filename + " has an incorrect entry for " + path);
            Logger.warn("Entry: " + value);
        }
        return null;
    }

}
