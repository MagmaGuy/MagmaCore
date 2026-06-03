# MagmaCore

Shared library and framework that backs the MagmaGuy plugin ecosystem
(EliteMobs, FreeMinecraftModels, ResourcePackManager, BetterStructures, and
others). It bundles two things consumers would otherwise each reimplement:

- **Cross-version NMS abstraction** — a single API over many Minecraft server
  internals, with one reobfuscated adapter compiled per server revision.
- **EasyMinecraftGoals** — a packet-based fake/"client-side" entity API
  (`com.magmaguy.easyminecraftgoals`) for spawning model, text, item and
  interaction entities that exist only as packets, plus pathfinding/goal helpers
  (wander-back-to-point, move, hitboxes, mass block edits).

It is **not** a standalone plugin. It is published as a library and **shaded**
into each consuming plugin's jar.

## Modules

This is a multi-module Gradle build (`settings.gradle.kts`):

- `core` — version-independent code: the `MagmaCore` entry point, config helpers
  (`ConfigurationFile`, `CustomConfig`, `ConfigurationEngine`), menus, commands,
  the match/instance system, world/region protection queries
  (`LocationQueryRegistry` with WorldGuard + GriefPrevention adapters), custom
  biome compatibility (`CustomBiomeCompatibility`), Lua scripting, the Nightbreak
  DLC/content pipeline, and shared utilities.
- `nms:core` — the version-independent EasyMinecraftGoals API and the
  `NMSManager`/`NMSAdapter` contract that runtime adapters implement.
- `nms:v1_19_R3` … `nms:v1_21_R7_*`, `nms:v26` — one adapter per supported server
  revision (see range below).
- `dist` — the shaded distribution module. Its `shadowJar` task assembles `core`,
  `nms:core` and every per-version adapter into a single `MagmaCore` jar
  (relocating `org.luaj` and `org.reflections` under `com.magmaguy.shaded`).

## Supported Minecraft versions

`NMSManager` selects an adapter at runtime from the bundled per-version modules,
spanning **Minecraft 1.19.4 through the 1.21.x line and the new year.drop
versioning (26.x)**:

| Adapter module        | Minecraft version(s) |
|-----------------------|----------------------|
| `v1_19_R3`            | 1.19.4               |
| `v1_20_R1`            | 1.20 / 1.20.1        |
| `v1_20_R2`            | 1.20.2               |
| `v1_20_R3`            | 1.20.3 / 1.20.4      |
| `v1_20_R4`            | 1.20.5 / 1.20.6      |
| `v1_21_R1`            | 1.21 / 1.21.1        |
| `v1_21_R2`            | 1.21.2 / 1.21.3      |
| `v1_21_R3`            | 1.21.4               |
| `v1_21_R4`            | 1.21.5               |
| `v1_21_R5`            | 1.21.6 / 1.21.7 / 1.21.8 |
| `v1_21_R6`            | 1.21.9 / 1.21.10     |
| `v1_21_R7_spigot` / `v1_21_R7_paper` | 1.21.11 (Paper hard-forked, so Spigot and Paper get separate adapters) |
| `v26`                 | 26.1+ (fully unobfuscated, single unified adapter) |

The authoritative mapping lives in
`nms/core/.../NMSManager#getInternalsFromRevision`.

## Consuming MagmaCore

MagmaCore is published to the MagmaGuy repository. Add the repo and depend on it,
then shade + relocate it into your plugin.

Coordinate:

```
com.magmaguy:MagmaCore:2.2.0-SNAPSHOT
```

Snapshots resolve from `https://repo.magmaguy.com/snapshots` (releases live at
`https://repo.magmaguy.com/releases`).

### Gradle (Kotlin DSL)

```kotlin
repositories {
    maven("https://repo.magmaguy.com/snapshots")
}

dependencies {
    implementation("com.magmaguy:MagmaCore:2.2.0-SNAPSHOT")
}
```

### Maven

```xml
<repository>
    <id>magmaguy-snapshots</id>
    <url>https://repo.magmaguy.com/snapshots</url>
</repository>

<dependency>
    <groupId>com.magmaguy</groupId>
    <artifactId>MagmaCore</artifactId>
    <version>2.2.0-SNAPSHOT</version>
</dependency>
```

Consumers shade the artifact into their final jar. Some relocate it under their
own namespace (e.g. FreeMinecraftModels shades it to
`com.magmaguy.freeminecraftmodels.magmacore`), while others ship it at its
original package — both are supported, since shared registries discover providers
across classloaders at runtime.

In your plugin, obtain the singleton via `MagmaCore.createInstance(yourPlugin)`
and, where you need packet entities/NMS, call
`NMSManager.initializeAdapter(yourPlugin)`.

## Building and publishing locally

JDK 21 is required. Use the Gradle wrapper.

Build the shaded distribution jar:

```bash
./gradlew :dist:shadowJar
```

The output `MagmaCore` jar lands in `dist/build/libs/`.

When you change MagmaCore source and want downstream plugins to pick up those
changes from their local build, install it to your local Maven repository:

```bash
./gradlew publishToMavenLocal
```

Consuming plugins resolve `mavenLocal()` first, so after a `publishToMavenLocal`
their next build shades in your local changes. (Some consumers pin a specific
MagmaCore version in their build files and must be bumped explicitly to pick up a
new one.)

## Known consumers

Plugins that depend on and shade MagmaCore include:

- EliteMobs
- FreeMinecraftModels
- ResourcePackManager
- BetterStructures
- CannonRTP
- EternalTD
- Extractioncraft
- MegaBlock Survivors
- ResurrectionChest

## License

No license file is currently present in this repository.
