# MagmaCore

Shared library and framework that backs the MagmaGuy plugin ecosystem
(EliteMobs, FreeMinecraftModels, ResourcePackManager, BetterStructures, and
others). It bundles two things consumers would otherwise each reimplement:

- **Cross-version NMS abstraction**: a single API over many Minecraft server
  internals, with one reobfuscated adapter compiled per server revision.
- **EasyMinecraftGoals**: a packet-based fake/"client-side" entity API
  (`com.magmaguy.easyminecraftgoals`) for spawning model, text, item and
  interaction entities that exist only as packets, plus pathfinding/goal helpers
  (wander-back-to-point, move, hitboxes, mass block edits).

It is **not** a standalone plugin. It is published as a library and **shaded**
into each consuming plugin's jar.

## Modules

This is a multi-module Gradle build (`settings.gradle.kts`):

- `core`: version-independent code: the `MagmaCore` entry point, config helpers
  (`ConfigurationFile`, `CustomConfig`, `ConfigurationEngine`), menus, commands,
  the match/instance system, world/region protection queries
  (`LocationQueryRegistry` with WorldGuard + GriefPrevention adapters), custom
  biome compatibility (`CustomBiomeCompatibility`), Lua scripting, the Nightbreak
  DLC/content pipeline, and shared utilities.
- `nms:core`: the version-independent EasyMinecraftGoals API and the
  `NMSManager`/`NMSAdapter` contract that runtime adapters implement.
- `nms:v1_21_R3` … `nms:v1_21_R7_*`, `nms:v26`: one adapter per supported server
  revision (see range below).
- `dist`: the shaded distribution module. Its `shadowJar` task assembles `core`,
  `nms:core` and every per-version adapter into a single `MagmaCore` jar
  (relocating `org.luaj` and `org.reflections` under `com.magmaguy.shaded`).

## Supported Minecraft versions

`NMSManager` selects an adapter at runtime from the bundled per-version modules,
spanning **Minecraft 1.21.4 through the rest of the 1.21.x line and the new
year.drop versioning (26.x)**. **1.21.4 is the support floor**: servers older
than that get a clear "unsupported Minecraft version" log line and NMS features
stay disabled.

| Adapter module        | Minecraft version(s) |
|-----------------------|----------------------|
| `v1_21_R3`            | 1.21.4               |
| `v1_21_R4`            | 1.21.5               |
| `v1_21_R5`            | 1.21.6 / 1.21.7 / 1.21.8 |
| `v1_21_R6`            | 1.21.9 / 1.21.10     |
| `v1_21_R7_spigot` / `v1_21_R7_paper` | 1.21.11 (Paper hard-forked, so Spigot and Paper get separate adapters) |
| `v26`                 | 26.1+ (fully unobfuscated, single unified adapter) |

The adapter mapping lives in [NMSManager](nms/core/src/main/java/com/magmaguy/easyminecraftgoals/NMSManager.java). A matching adapter is not a claim that every feature has been tested on every server version.

The pre-1.21.4 adapter modules (`v1_19_R3`, `v1_20_R1`–`v1_20_R4`, `v1_21_R1`,
`v1_21_R2`) have been removed from the working tree and from
`settings.gradle.kts` / `dist/build.gradle.kts`. Recover their sources from git
history as well as restoring their Gradle entries if support below 1.21.4 is
ever required again.

## Shared plugin services

The `core` module also owns shared custom enchantments and item actions, Nightbreak account and update handling, content catalogs and setup menus, text displays, and cross-plugin protection queries. Consumers should use these existing owners rather than create a second implementation of the same behavior.

## Native Mind adapters

All active adapters compile the canonical runtime from `nms/mind-shared/src/main/java`.
The root build generates adapter-local packages, matching the existing R7 shared-source
pattern. Only `NativeMindVersion` contains signatures that differ between native versions;
Mind programs, lifecycle, controls and movement arbitration remain shared.

Factory-created bodies suppress native decisions while preserving native movement and
combat events. Missing melee attributes are supplied for passive species. Body preparation
can bind the program and apply consumer configuration before `CreatureSpawnEvent` fires;
rejection closes the attached session and discards the body. The consumer must roll back
its own bookkeeping. Existing pathfinding handles use the Mind clock and movement lease,
yield to combat and flee, and pause alongside the Mind. Pausing decisions retains native
physical movement such as gravity for non-stationary profiles.

Sunlight suppression is limited to marked, susceptible bodies exposed to daylight and
plain combustion events. Entity/block-caused combustion remains untouched. Native goals
are removed and NoAI prevents zombie underwater conversion; unrelated damage remains native.

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
original package: both are supported, since shared registries discover providers
across classloaders at runtime.

In your plugin, obtain the singleton via `MagmaCore.createInstance(yourPlugin)`
and, where you need packet entities/NMS, call
`NMSManager.initializeAdapter(yourPlugin)`.

## Building and publishing locally

Build with JDK 21 and the Gradle wrapper. On Windows, replace `./gradlew` below with `.\gradlew.bat`. A server running a consuming plugin must also meet that server release's Java requirements.

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

Consumers configured to resolve Maven Local can pick up the locally published artifact. Check their repository order and dependency version, then rebuild each consuming plugin to shade the changed library. (Some consumers pin a specific
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
- BetterFood

## License

No license file is currently present in this repository.
