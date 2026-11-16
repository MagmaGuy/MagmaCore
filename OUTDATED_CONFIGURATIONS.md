# Archiving outdated configurations

MagmaCore owns detection and archival. A plugin declares retirement rules by
content directory in a bundled resource named
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

## Filename-scoped value rules

A `files`/`key`/`value` rule matches an exact string or string list at a
dot-separated mapping path. For example:

```yaml
customquests:
  - files: [ag_welcome_quest_1.yml]
    key: customObjectives.Objective13.filename
    value: scroll_applier_config.yml
```

This archives only that filename in `customquests` or its subdirectories when
the specified objective still references Scotty. The replacement default must
omit that objective so it does not match on subsequent starts. Filenames are
explicit, with no wildcards. Paths traverse mappings only, with no list indexes,
wildcards, or literal dotted keys. Values must be nonblank strings or nonempty
lists of nonblank strings and match exactly, including case. List order,
duplicates and additional entries are significant. Missing paths and values of
other types do not match.
The original top-level key rules and `listEntryNames` rules keep their existing
semantics.

For example, retire Casus's old one-quest assignment with:

```yaml
npcs:
  - files: [guide_1.yml]
    key: questFileName
    value: [ag_welcome_quest_1.yml]
```

His current four-quest default does not match. Neither does a customized list
with additional quests. Other customized settings in a matching file are
preserved in the archive, but the active replacement uses current defaults.

## Stale key rules (edit in place)

A whole-file archive resets every other setting in that file. When only one key
is stale in a file admins customize, a `removeKey` rule deletes that key, and a
`replaceKey` rule swaps an old default for the new one, only while the key holds
one of the values a release shipped:

```yaml
menus:
  - files: [player_status_screen.yml]
    removeKey: skillsItemDisplayName
    shippedValues: ['&5&lSkills', '<g:#6D3AA8:#A855F7>⚗ Skills</g>']
command_messages.yml:
  - files: [command_messages.yml]
    replaceKey: skillTestAlreadyRunningMessage
    shippedValues: ['&cOld default text']
    newValue: '&cNew default text'
```

- `shippedValues` lists alternatives. Each is a nonblank string, a boolean, a
  finite number (compared by value) or a string list (written as a nested list,
  for example `[[BARRIER]]`). Comparison is exact; any other value is a
  customization and stays.
- Stale key rules never archive a file. They run after the archive pass, at the
  same points, on the files that remain.
- A changed file is loaded and saved through Bukkit's YAML, as the plugin's own
  loaders do (comments kept), and written only when a key changed. One log line
  names each changed file and its keys.
- Unparsable or non-UTF-8 files, links, and files that change while being
  checked are left alone, with a warning for the last two.
- Do not use these rules on keys DLC ships, since the next download writes them
  back, or on top-level keys of `ConfigurationFile` singletons, which already
  drop keys that are no longer defaults.

## Behavior

- A matching rule archives the whole `.yml` or `.yaml` file, including customized
  contents. For top-level key rules, null, false and empty values still count as
  key presence. Comments, string values and identically named keys nested under
  another key do not.
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
`OutdatedConfigurationArchive.archiveFor(plugin)` before their configuration loaders.
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
