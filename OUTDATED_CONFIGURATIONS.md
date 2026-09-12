# Archiving outdated configurations

MagmaCore owns detection and archival. A plugin declares exact retired top-level
YAML keys by content directory in a bundled resource named
`outdated-config-keys.yml`. This resource stays inside the plugin JAR; it is not
copied into the administrator's configuration or inferred from filenames.

For example, after a plugin and its current content have stopped writing these
keys:

```yaml
customitems:
  - enchantmentsV2
powers/some_category:
  - retiredPowerOption
```

This is an example policy, not an active EliteMobs retirement declaration.
Declare only directories containing configurations owned by this policy. Changing
a key requires updating the current reader, generated defaults, exporters and
affected DLC before activating its retirement. A key still used by a supported
configuration in the same directory cannot be retired there.

## Behavior

- A matching key archives the whole `.yml` or `.yaml` file, including customized
  contents. Null, false and empty values still count as key presence. Comments,
  string values and identically named keys nested under another key do not.
- Original bytes are moved without YAML reserialization. No content merge,
  historical hash inventory, customized-file exemption or automatic conversion
  is involved.
- Archives live outside plugin content roots at
  `plugins/MagmaCore/outdated files/<plugin>/<unique batch>/<original relative path>`.
  Category directories and all nested folders are preserved. Existing archives
  are never overwritten, and nothing automatically prunes them.
- The archive directory is created only when at least one file matches. A repeat
  scan with no new retired files does nothing. Importing another old copy creates
  a separate batch.
- Invalid or duplicate-key YAML, unsafe paths, changed files and failed moves
  stop the operation. Files already moved remain safely archived; files not moved
  remain at their original paths. The archive is not rolled back automatically.
  Symbolic links and redirected paths are refused. Inspection is bounded to 4 MiB
  per YAML file.

## Integration

`MagmaCore.startInitialization` scans before the plugin's asynchronous
initialization callback, allowing ordinary default generation to recreate
missing current defaults. `ConfigurationImporter` scans declared categories
before processing imports and again afterward, before model-installation events.
A pack may target several plugins, so the importer reads each enabled target's
resource through Bukkit rather than another shaded library's static registry.
Matching incoming old YAML is archived after import as well.

Plugins with a separate initialization path can call
`OutdatedConfigurationArchive.archive(plugin)` before their configuration loaders.
The method throws on failure so callers must not continue loading or overwriting
files after unsuccessful archival. It does not download replacement DLC.

The archive sits outside active plugin trees. Shared `ContentFileSelector` ignores
its reserved path, and `ConfigurationEngine.fileConfigurationCreator` refuses
direct reads from it. The archive is for manual safekeeping; no loader or importer
should register it as an input directory.

## Focused verification

```powershell
.\gradlew.bat :core:test --tests com.magmaguy.magmacore.config.OutdatedConfigurationArchiveTest
```

These tests exercise actual YAML parsing and filesystem moves, including edited
files, nested paths, category isolation, repeated imports, archive exclusions and
failure preservation. They do not establish an end-to-end plugin/DLC upgrade;
that check must use the corresponding updated readers and content.
