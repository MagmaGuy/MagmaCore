# Changelog

## 1.26
- Added /nightbreakLogin command for registering Nightbreak account tokens for DLC access
- Added advanced color support to ChatColorConverter: hex colors (&#RRGGBB, <#RRGGBB>), gradients (gradient:#START:#ENDtext), multi-color gradients, and rainbow text
- Added fallback to nearest legacy color for servers without hex color support
- Added OUT_OF_DATE_UPDATABLE and OUT_OF_DATE_NO_ACCESS content states to setup menu
- Added custom title support to SetupMenu constructor
- Added @Getter to args field in CommandData
- Fixed ArrayIndexOutOfBoundsException in AdvancedCommand tab completion when args is empty
- Fixed future materials (e.g. spears) crashing CustomConfigFields instead of returning null silently
- Fixed future spear materials silently returning default instead of logging warnings in CustomConfigFields
- Fixed player messages being sent to disconnected players during async downloads in NightbreakContentManager
- Fixed nightbreakLogin command casing to use lowercase (nightbreaklogin) for Minecraft command convention
- Improved NightbreakAccount error logging to include the failing URL
- Setup menu now closes inventory on content package click

## 1.23
- Fixed directory creation in ConfigurationImporter to properly create parent directories when target plugin isn't installed
- Fixed ZipFile directory detection for Windows-style zips using backslashes
- Fixed ZipFile path normalization to handle both forward and back slashes
- Fixed setLastModified only being called when zip entry has valid timestamp

## 1.22
- Added DrawLine utility for drawing lines between points using BlockDisplay entities
- Added SchematicManager for WorldEdit schematic loading and pasting operations
- Added WorldEdit dependency
- Added support for BlockBench v5 model format with groups/outliner merging
- Added resource_pack folder import support for EliteMobs
- Added sendDialog method to DialogManager
- Improved match instance system to support multiple worlds per instance
- Added addNewPlayer overload to MatchInstance
- Made victory and defeat methods public
- Renamed startMatch to finishStarting for clarity
- Minor cleanup and removed debug logging
