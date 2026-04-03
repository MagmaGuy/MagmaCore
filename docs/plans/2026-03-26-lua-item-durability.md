# Lua Item Durability API — Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Add durability read/write methods to the FMM `context.item` Lua table so scripters can check remaining durability and reduce it (flat or percentage), with control over whether the item breaks.

**Architecture:** All methods go in `FreeMinecraftModels/src/main/java/com/magmaguy/freeminecraftmodels/scripting/LuaItemTable.java`, alongside the existing `get_uses`, `set_uses`, `consume`, etc. They use `findEquippedItem(player, itemId)` to resolve the specific scripted item, then operate via the `Damageable` ItemMeta interface. Mutations run on the main thread via `Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, ...)` — the same pattern every existing mutating method in this file uses. FMM uses shaded LuaJ (`com.magmaguy.shaded.luaj.vm2`).

**Tech Stack:** Java 21, shaded LuaJ (`com.magmaguy.shaded.luaj.vm2`), Bukkit/Spigot API (`Damageable`, `ItemStack`)

---

## API surface (what scripters will call)

```lua
-- Returns table { current = <int>, max = <int> } or nil if item has no durability
local dur = context.item:get_durability()

-- Reduce durability by a flat amount. can_break controls whether the item is destroyed.
context.item:use_durability(amount, can_break)

-- Reduce durability by a percentage (0.0–1.0) of max. can_break controls destruction.
context.item:use_durability_percentage(fraction, can_break)
```

**`can_break` behaviour:**
- `true` — if remaining durability hits 0, the item is destroyed (amount set to 0).
- `false` (default) — durability is clamped to `max - 1`, leaving the item one tick from breaking.

---

## Task 1: Add `get_durability` method

**Files:**
- Modify: `FreeMinecraftModels/src/main/java/com/magmaguy/freeminecraftmodels/scripting/LuaItemTable.java`

**Step 1: Add the Damageable import**

At the top of the file, after the existing `import org.bukkit.inventory.meta.ItemMeta;` line, add:

```java
import org.bukkit.inventory.meta.Damageable;
```

**Step 2: Write the method**

Insert after the `set_uses` block (after line 105), before the `get_name` block:

```java
        // get_durability() — returns {current, max} or nil if item has no durability bar
        table.set("get_durability", LuaTableSupport.tableMethod(table, args -> {
            ItemStack item = findEquippedItem(player, itemId);
            if (item == null || item.getType().getMaxDurability() == 0) return LuaValue.NIL;
            ItemMeta meta = item.getItemMeta();
            if (!(meta instanceof Damageable damageable)) return LuaValue.NIL;
            int max = item.getType().getMaxDurability();
            LuaTable result = new LuaTable();
            result.set("current", max - damageable.getDamage());
            result.set("max", max);
            return result;
        }));
```

**Step 3: Build to verify**

Run from the FreeMinecraftModels root:
```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/scripting/LuaItemTable.java
git commit -m "feat(lua): add get_durability to item table"
```

---

## Task 2: Add `use_durability` method (flat amount)

**Files:**
- Modify: `FreeMinecraftModels/src/main/java/com/magmaguy/freeminecraftmodels/scripting/LuaItemTable.java` (right after `get_durability`)

**Step 1: Write the method**

Insert after `get_durability`:

```java
        // use_durability(amount, can_break) — reduces durability by a flat amount
        table.set("use_durability", LuaTableSupport.tableMethod(table, args -> {
            int amount = args.checkint(1);
            boolean canBreak = args.narg() >= 2 && !args.arg(2).isnil() && args.arg(2).checkboolean();
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item == null || item.getType().getMaxDurability() == 0) return;
                ItemMeta meta = item.getItemMeta();
                if (!(meta instanceof Damageable damageable)) return;
                int max = item.getType().getMaxDurability();
                int newDamage = damageable.getDamage() + amount;
                if (newDamage >= max) {
                    if (canBreak) {
                        item.setAmount(0);
                    } else {
                        damageable.setDamage(max - 1);
                        item.setItemMeta(damageable);
                    }
                } else {
                    damageable.setDamage(Math.max(0, newDamage));
                    item.setItemMeta(damageable);
                }
            });
            return LuaValue.NIL;
        }));
```

**Step 2: Build to verify**

```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/scripting/LuaItemTable.java
git commit -m "feat(lua): add use_durability method to item table (flat durability loss)"
```

---

## Task 3: Add `use_durability_percentage` method

**Files:**
- Modify: `FreeMinecraftModels/src/main/java/com/magmaguy/freeminecraftmodels/scripting/LuaItemTable.java` (right after `use_durability`)

**Step 1: Write the method**

Insert after `use_durability`:

```java
        // use_durability_percentage(fraction, can_break) — reduces durability by a percentage of max
        table.set("use_durability_percentage", LuaTableSupport.tableMethod(table, args -> {
            double fraction = args.checkdouble(1);
            boolean canBreak = args.narg() >= 2 && !args.arg(2).isnil() && args.arg(2).checkboolean();
            Bukkit.getScheduler().runTask(MetadataHandler.PLUGIN, () -> {
                ItemStack item = findEquippedItem(player, itemId);
                if (item == null || item.getType().getMaxDurability() == 0) return;
                ItemMeta meta = item.getItemMeta();
                if (!(meta instanceof Damageable damageable)) return;
                int max = item.getType().getMaxDurability();
                int amount = (int) Math.ceil(max * fraction);
                int newDamage = damageable.getDamage() + amount;
                if (newDamage >= max) {
                    if (canBreak) {
                        item.setAmount(0);
                    } else {
                        damageable.setDamage(max - 1);
                        item.setItemMeta(damageable);
                    }
                } else {
                    damageable.setDamage(Math.max(0, newDamage));
                    item.setItemMeta(damageable);
                }
            });
            return LuaValue.NIL;
        }));
```

**Step 2: Build to verify**

```bash
./gradlew compileJava
```
Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add src/main/java/com/magmaguy/freeminecraftmodels/scripting/LuaItemTable.java
git commit -m "feat(lua): add use_durability_percentage method to item table"
```

---

## Summary of changes

All changes in one file: `FreeMinecraftModels/src/main/java/com/magmaguy/freeminecraftmodels/scripting/LuaItemTable.java`

| Method | Args | Returns | Description |
|--------|------|---------|-------------|
| `get_durability()` | none | `{current, max}` or nil | Read remaining & max durability |
| `use_durability(amount, can_break)` | int, bool | nil | Flat durability reduction |
| `use_durability_percentage(fraction, can_break)` | double, bool | nil | % of max durability reduction |

All methods use `findEquippedItem(player, itemId)` to resolve the specific scripted item. All mutations are thread-safe (`runTask`). `can_break` defaults to `false` (item preserved at 1 durability remaining).
