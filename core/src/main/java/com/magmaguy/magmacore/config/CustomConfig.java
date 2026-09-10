package com.magmaguy.magmacore.config;


import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.util.Logger;
import org.bukkit.configuration.file.FileConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.reflections.Reflections;

import java.io.File;
import java.lang.reflect.Constructor;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;

public class CustomConfig {

    //This stores configurations long term, ? is the specific extended custom config field
    private final HashMap<String, CustomConfigFields> customConfigFieldsHashMap = new HashMap<>();
    //This is only used for loading configurations in to check if the machine has all of the default files
    private final List<CustomConfigFields> customConfigFieldsArrayList = new ArrayList<>();
    private final String folderName;
    private final Class<? extends CustomConfigFields> customConfigFields;
    private final CustomConfigInheritancePolicy inheritancePolicy;

    public CustomConfig(String folderName, Class<? extends CustomConfigFields> customConfigFields, CustomConfigFields schematicConfigField) {
        this.folderName = folderName;
        this.customConfigFields = customConfigFields;
        this.inheritancePolicy = null;
        initialize(schematicConfigField);
    }

    public CustomConfig(String folderName, Class<? extends CustomConfigFields> customConfigFields) {
        this.folderName = folderName;
        this.customConfigFields = customConfigFields;
        this.inheritancePolicy = null;
    }

    /**
     * Initializes all configurations and stores them in a list for later access
     */
    public CustomConfig(String folderName, String packageName, Class<? extends CustomConfigFields> customConfigFields) {
        this(folderName, packageName, customConfigFields, null);
    }

    public CustomConfig(String folderName,
                        String packageName,
                        Class<? extends CustomConfigFields> customConfigFields,
                        CustomConfigInheritancePolicy inheritancePolicy) {
        this.folderName = folderName;
        this.customConfigFields = customConfigFields;
        this.inheritancePolicy = inheritancePolicy;

        String directory = MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getAbsolutePath() + File.separatorChar + folderName;
        File file = Path.of(directory).toFile();
        if (!file.exists()) file.mkdir();

        //Case if there are no premade configurations in the premade package, otherwise reflections error
        if (packageName.isEmpty()) return;

        //Set defaults through reflections by getting everything that extends specific CustomConfigFields within specific package scopes
        Reflections reflections = new Reflections(packageName);

        try {
            Set<Class> classSet = new HashSet<>(reflections.getSubTypesOf(customConfigFields));
            classSet.forEach(aClass -> {
                // Reflections includes shared abstract bases through subtype expansion.
                if (java.lang.reflect.Modifier.isAbstract(aClass.getModifiers())) return;
                try {
                    customConfigFieldsArrayList.add((CustomConfigFields) aClass.getDeclaredConstructor().newInstance());
                } catch (Exception ex) {
                    Logger.warn("Failed to generate plugin default classes for " + folderName + " ! This is very bad, warn the developer!");
                    ex.printStackTrace();
                }
            });
        } catch (Exception e) {
            //In some plugins the premades are empty which causes this error
        }

        //Check if the directory doesn't exist
        try {
            if (!Files.isDirectory(Paths.get(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getPath() + File.separatorChar + folderName))) {
                generateFreshConfigurations();
                return;
            }
        } catch (Exception ex) {
            Logger.warn("Failed to generate plugin default files for " + folderName + " ! This is very bad, warn the developer!");
            ex.printStackTrace();
            return;
        }

        //Runs if the directory exists
        //Check if all the defaults exist
        if (inheritancePolicy == null)
            for (File selected : ContentFileSelector.select(collectYamlFiles(file)))
                fileInitializer(selected);
        else {
            initializeInheritanceAware(file);
            return;
        }

        try {
            //Generate missing default config files, might've been deleted or might have been added in newer version
            if (!customConfigFieldsArrayList.isEmpty())
                generateFreshConfigurations();
        } catch (Exception ex) {
            Logger.warn("Failed to finish generating default plugin files for " + folderName + " ! This is very bad, warn the developer!");
            ex.printStackTrace();
        }

    }

    /** Adds or replaces one file at runtime, using the same inheritance resolver as startup. */
    public synchronized CustomConfigFields registerFile(File file) {
        Objects.requireNonNull(file, "file");
        if (!file.getName().toLowerCase(Locale.ROOT).endsWith(".yml"))
            throw new IllegalArgumentException("Custom configuration files must end in .yml");
        Path root = configurationDirectory().toPath().toAbsolutePath().normalize();
        Path candidate = file.toPath().toAbsolutePath().normalize();
        if (!candidate.startsWith(root))
            throw new IllegalArgumentException("Configuration file is outside " + root);

        List<File> candidates = new ArrayList<>(collectYamlFiles(configurationDirectory()));
        if (!candidates.contains(file)) candidates.add(file);
        if (inheritancePolicy == null) {
            File selected = ContentFileSelector.select(candidates).stream()
                    .filter(source -> source.getName().equals(file.getName())).findFirst().orElseThrow();
            if (candidate.equals(selected.toPath().toAbsolutePath().normalize())
                    || !isLoadedFrom(selected))
                initialize(selected);
            return customConfigFieldsHashMap.get(selected.getName());
        }

        InheritanceResolver resolver = new InheritanceResolver(candidates);
        File selected = resolver.fileFor(normalizeFilename(file.getName()));
        customConfigFieldsHashMap.keySet().removeIf(name -> !name.equals(selected.getName())
                && normalizeFilename(name).equals(normalizeFilename(selected.getName())));
        if (candidate.equals(selected.toPath().toAbsolutePath().normalize())
                || !isLoadedFrom(selected))
            initializeResolved(selected, null, resolver);
        return customConfigFieldsHashMap.get(selected.getName());
    }

    private boolean isLoadedFrom(File source) {
        CustomConfigFields loaded = customConfigFieldsHashMap.get(source.getName());
        return loaded != null && loaded.getFile() != null
                && loaded.getFile().toPath().toAbsolutePath().normalize()
                .equals(source.toPath().toAbsolutePath().normalize());
    }

    private void initializeInheritanceAware(File directory) {
        List<File> existingFiles = collectYamlFiles(directory);
        Set<String> existingNames = new HashSet<>();
        for (File existingFile : existingFiles)
            existingNames.add(normalizeFilename(existingFile.getName()));

        Map<String, CustomConfigFields> premades = new HashMap<>();
        for (CustomConfigFields premade : customConfigFieldsArrayList)
            premades.put(normalizeFilename(premade.getFilename()), premade);

        Set<String> alreadyInitialized = new HashSet<>();
        for (Map.Entry<String, CustomConfigFields> entry : premades.entrySet()) {
            if (existingNames.contains(entry.getKey())) continue;
            initialize(entry.getValue());
            alreadyInitialized.add(entry.getKey());
        }

        List<File> files = collectYamlFiles(directory);
        InheritanceResolver resolver = new InheritanceResolver(files);
        for (File file : files) {
            String key = normalizeFilename(file.getName());
            if (alreadyInitialized.contains(key)) continue;
            if (resolver.fileFor(key) != file) continue;
            initializeResolved(file, premades.get(key), resolver);
        }
        customConfigFieldsArrayList.clear();
    }

    private void initializeResolved(File file,
                                    CustomConfigFields premade,
                                    InheritanceResolver resolver) {
        CustomConfigFields fields = premade;
        try {
            if (fields == null) {
                Constructor<?> constructor = customConfigFields.getConstructor(String.class, boolean.class);
                fields = (CustomConfigFields) constructor.newInstance(file.getName(), true);
            }

            InheritanceResolver.ResolvedConfiguration resolved = resolver.resolve(file);
            fields.setFile(file);
            if (resolved.inherited())
                fields.beginInheritedRead(resolved.readConfiguration(), resolved.rawConfiguration());
            else
                fields.setFileConfiguration(resolved.rawConfiguration());
            fields.processConfigFields();

            // Sparse leaves remain sparse: inherited values and parser defaults never leak into them.
            if (!resolved.inherited())
                ConfigurationEngine.fileSaverCustomValues(resolved.rawConfiguration(), file);
            addCustomConfigFields(file.getName(), fields);
        } catch (Exception exception) {
            Logger.warn("Disabled inherited configuration " + file.getName() + ": " + rootMessage(exception));
            try {
                if (fields == null) {
                    Constructor<?> constructor = customConfigFields.getConstructor(String.class, boolean.class);
                    fields = (CustomConfigFields) constructor.newInstance(file.getName(), false);
                }
                fields.setFile(file);
                fields.setFileConfiguration(YamlConfiguration.loadConfiguration(file));
                fields.setEnabled(false);
                addCustomConfigFields(file.getName(), fields);
            } catch (Exception constructionFailure) {
                Logger.warn("Could not retain disabled configuration " + file.getName() + ": "
                        + rootMessage(constructionFailure));
            }
        }
    }

    private File configurationDirectory() {
        return Path.of(MagmaCore.getInstance().getRequestingPlugin().getDataFolder().getPath(), folderName).toFile();
    }

    private static List<File> collectYamlFiles(File directory) {
        if (directory == null || !directory.isDirectory()) return List.of();
        try (var paths = Files.walk(directory.toPath())) {
            return paths.filter(Files::isRegularFile)
                    .map(Path::toFile)
                    .filter(file -> file.getName().toLowerCase(Locale.ROOT).endsWith(".yml"))
                    .sorted(Comparator.comparing(File::getAbsolutePath, String.CASE_INSENSITIVE_ORDER))
                    .toList();
        } catch (Exception exception) {
            Logger.warn("Failed to enumerate inherited configurations in " + directory + ": "
                    + rootMessage(exception));
            return List.of();
        }
    }

    private static String normalizeFilename(String filename) {
        String normalized = filename.trim().toLowerCase(Locale.ROOT);
        return normalized.endsWith(".yml") ? normalized : normalized + ".yml";
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) current = current.getCause();
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private final class InheritanceResolver {
        private final Map<String, File> files = new HashMap<>();
        private final Map<String, ResolvedConfiguration> resolved = new HashMap<>();
        private final LinkedHashSet<String> resolving = new LinkedHashSet<>();

        private InheritanceResolver(List<File> sourceFiles) {
            for (File sourceFile : ContentFileSelector.select(sourceFiles, CustomConfig::normalizeFilename))
                files.put(normalizeFilename(sourceFile.getName()), sourceFile);
        }

        private File fileFor(String normalizedFilename) {
            return files.get(normalizedFilename);
        }

        private ResolvedConfiguration resolve(File file) {
            String key = normalizeFilename(file.getName());
            ResolvedConfiguration cached = resolved.get(key);
            if (cached != null) return cached;
            if (!resolving.add(key)) {
                List<String> cycle = new ArrayList<>(resolving);
                cycle.add(key);
                throw new IllegalArgumentException("extends cycle: " + String.join(" -> ", cycle));
            }

            try {
                YamlConfiguration raw = YamlConfiguration.loadConfiguration(file);
                String parent = raw.getString(inheritancePolicy.parentKey());
                if (parent == null || parent.isBlank()) {
                    ResolvedConfiguration result = new ResolvedConfiguration(raw, raw, false);
                    resolved.put(key, result);
                    return result;
                }
                if (parent.contains("/") || parent.contains("\\"))
                    throw new IllegalArgumentException("extends must name a file in the same configuration catalog");
                String parentKey = normalizeFilename(parent);
                File parentFile = files.get(parentKey);
                if (parentFile == null)
                    throw new IllegalArgumentException("missing extends base " + parentKey);

                ResolvedConfiguration base = resolve(parentFile);
                YamlConfiguration merged = new YamlConfiguration();
                copyLeaves(base.readConfiguration(), merged, true);
                copyLeaves(raw, merged, false);
                ResolvedConfiguration result = new ResolvedConfiguration(merged, raw, true);
                resolved.put(key, result);
                return result;
            } finally {
                resolving.remove(key);
            }
        }

        private void copyLeaves(FileConfiguration source, YamlConfiguration target, boolean inheritedValues) {
            for (Map.Entry<String, Object> entry : source.getValues(true).entrySet()) {
                String path = entry.getKey();
                Object value = entry.getValue();
                if (value instanceof org.bukkit.configuration.ConfigurationSection) continue;
                if (path.equalsIgnoreCase(inheritancePolicy.parentKey())) {
                    if (!inheritedValues) target.set(path, value);
                    continue;
                }
                if (inheritedValues && !inheritancePolicy.mayInherit(path)) continue;
                target.set(path, value);
            }
        }

        private record ResolvedConfiguration(
                FileConfiguration readConfiguration,
                FileConfiguration rawConfiguration,
                boolean inherited) {
        }
    }

    private void fileInitializer(File file) {
        for (Iterator<CustomConfigFields> iterator = customConfigFieldsArrayList.iterator(); iterator.hasNext();) {
            CustomConfigFields premade = iterator.next();
            if (file.getName().equalsIgnoreCase(premade.getFilename())) {
                iterator.remove();
                try {
                    initialize(premade, file);
                } catch (Exception exception) {
                    Logger.warn("Failed to read plugin files for " + folderName + " ! This is very bad, warn the developer!");
                    exception.printStackTrace();
                }
                return;
            }
        }
        initialize(file);
    }

    public HashMap<String, ? extends CustomConfigFields> getCustomConfigFieldsHashMap() {
        return customConfigFieldsHashMap;
    }

    /**
     * Adds entry to custom config fields. This is done directly by the custom config fields as they are iterated through.
     *
     * @param filename           Name of the file , using the format filename.yml
     * @param customConfigFields Custom Config Fields, should be from an extended subclass
     */
    public <V extends CustomConfigFields> void addCustomConfigFields(String filename, CustomConfigFields customConfigFields) {
        customConfigFieldsHashMap.put(filename, customConfigFields);
    }

    /**
     * Called when the appropriate configurations directory does not exist
     */
    private void generateFreshConfigurations() {
        for (Object customConfigFields : customConfigFieldsArrayList)
            initialize((CustomConfigFields) customConfigFields);
    }

    /**
     * Initializes a single instance of a premade configuration using the default values. Writes defaults.
     */
    private void initialize(CustomConfigFields customConfigFields) {
        //Create configuration file from defaults if it does not exist
        File file = ConfigurationEngine.fileCreator(folderName, customConfigFields.getFilename());
        initialize(customConfigFields, file);
    }

    private void initialize(CustomConfigFields customConfigFields, File file) {
        //Get config file
        FileConfiguration fileConfiguration = ConfigurationEngine.fileConfigurationCreator(file);

        //Associate config
        customConfigFields.setFile(file);
        customConfigFields.setFileConfiguration(fileConfiguration);

        //Parse actual fields and load into RAM to be used
        customConfigFields.processConfigFields();

        //Save all configuration values as they exist
        ConfigurationEngine.fileSaverCustomValues(fileConfiguration, file);

        //if (customConfigFields.isEnabled)
        //Store for use by the plugin
        addCustomConfigFields(file.getName(), customConfigFields);
    }

    /**
     * Called when a user-made file is detected.
     */
    private void initialize(File file) {
        //Load file configuration from file
        try {
            if (!file.getName().endsWith(".yml")) return;
            FileConfiguration fileConfiguration = YamlConfiguration.loadConfiguration(file);
            //Instantiate the correct CustomConfigFields instance
            Constructor<?> constructor = customConfigFields.getConstructor(String.class, boolean.class);
            CustomConfigFields instancedCustomConfigFields = (CustomConfigFields) constructor.newInstance(file.getName(), true);
            instancedCustomConfigFields.setFileConfiguration(fileConfiguration);
            instancedCustomConfigFields.setFile(file);
            //Parse actual fields and load into RAM to be used
            instancedCustomConfigFields.processConfigFields();
            //Persist any newly-defaulted keys (e.g. options added in a newer version) back to disk so they
            //become visible and editable. fileSaverCustomValues uses copyDefaults(true) which only writes
            //missing defaults; it never overwrites existing user-set values or strips comments. This mirrors
            //the fresh-generation path so pre-existing user files also gain newly added defaults.
            ConfigurationEngine.fileSaverCustomValues(fileConfiguration, file);
            //if (instancedCustomConfigFields.isEnabled)
            //Store for use by the plugin
            addCustomConfigFields(file.getName(), instancedCustomConfigFields);
        } catch (Exception ex) {
            Logger.warn("Bad constructor for file " + file.getName() + " ! You should probably delete that file.");
            ex.printStackTrace();
        }

    }

}
