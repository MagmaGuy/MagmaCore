# MagmaCore

Shared library for Nightbreak plugins, including configuration, commands, menus,
content services, Lua scripting, matches and cross-version NMS support. It is
shaded into each consumer's JAR and is not a standalone Minecraft plugin.

## Developer documentation

[Java class and method reference](https://wiki.nightbreak.io/javadoc/magmacore/index.html).

The [MagmaCore developer guide](https://wiki.nightbreak.io/developers/magmacore)
covers dependency setup, shading, classloaders, initialization, shutdown,
protection queries and NMS adapters. The [Java API index](https://wiki.nightbreak.io/developers)
links the individual plugin APIs.

Coordinate: `com.magmaguy:MagmaCore:2.2.0-SNAPSHOT`, available from
[MagmaGuy's snapshots repository](https://repo.magmaguy.com/#/snapshots).
Consumers include the library in their shaded output.

## Source structure

| Module | Purpose |
| --- | --- |
| `core` | Shared plugin services and lifecycle |
| `nms:core` | EasyMinecraftGoals API and NMS adapter contract |
| `nms:v*` | Server-version adapter implementations |
| `dist` | Shaded MagmaCore distribution |

The current NMS adapter set starts at Minecraft 1.21.4. See the developer guide
for the version map and runtime availability checks.

## Building locally

Use JDK 21 and the Gradle wrapper. On Windows:

```powershell
.\gradlew.bat :dist:shadowJar
.\gradlew.bat publishToMavenLocal
```

On Unix, use `./gradlew` with the same tasks. The shaded library is written to
`dist/build/libs/`. Publish to Maven Local before rebuilding consumers that need
your library changes; each consumer must resolve the intended version and shade
it into its own new JAR.

## License

No license file is currently present in this repository.
