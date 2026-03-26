package com.magmaguy.magmacore.scripting.tables;

import org.bukkit.Bukkit;
import org.bukkit.entity.Entity;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;

import java.lang.reflect.Method;
import java.util.HashMap;
import java.util.logging.Level;

/**
 * Reflection-based bridge that adds plugin-specific fields to Lua entity tables
 * without creating compile-time dependencies on EliteMobs or FreeMinecraftModels.
 * <p>
 * Methods and classes are resolved once on first use and cached for performance.
 * If a plugin is not installed, all related checks return nil/false gracefully.
 */
final class LuaEntityBridge {

    // ── EliteMobs reflection cache ──────────────────────────────────────
    private static boolean eliteResolved = false;
    private static Method isEliteMobMethod;       // EntityTracker.isEliteMob(Entity) -> boolean
    private static Method getEliteMobEntityMethod; // EntityTracker.getEliteMobEntity(Entity) -> EliteEntity
    private static Method eliteGetLevelMethod;     // EliteEntity.getLevel() -> int
    private static Method eliteGetNameMethod;      // EliteEntity.getName() -> String
    private static Method eliteGetHealthMethod;    // EliteEntity.getHealth() -> double
    private static Method eliteGetMaxHealthMethod; // EliteEntity.getMaxHealth() -> double
    private static Method eliteIsCustomBossMethod; // EliteEntity.isCustomBossEntity() -> boolean
    private static Method eliteRemoveMethod;       // EliteEntity.remove(RemovalReason) -> void
    private static Object removalReasonOther;      // RemovalReason.OTHER enum constant

    // ── FreeMinecraftModels reflection cache ────────────────────────────
    private static boolean fmmResolved = false;
    private static Method isDynamicEntityMethod;   // DynamicEntity.isDynamicEntity(Entity) -> boolean
    private static Method getDynamicEntityMethod;  // DynamicEntity.getDynamicEntity(Entity) -> DynamicEntity
    private static Class<?> modeledEntityClass;
    private static Method getLoadedMapMethod;      // ModeledEntity.getLoadedModeledEntitiesWithUnderlyingEntities() -> HashMap
    private static Method getEntityIDMethod;       // ModeledEntity.getEntityID() -> String
    private static Method playAnimationMethod;     // ModeledEntity.playAnimation(String, boolean, boolean) -> boolean
    private static Method stopAnimationsMethod;    // ModeledEntity.stopCurrentAnimations() -> void
    private static Method modeledRemoveMethod;     // ModeledEntity.remove() -> void
    // PropEntity
    private static Class<?> propEntityClass;
    private static Method isPropEntityMethod;      // PropEntity.isPropEntity(ArmorStand) -> boolean
    private static Method getPropEntityIDMethod;   // PropEntity.getPropEntityID(ArmorStand) -> String

    private LuaEntityBridge() {}

    // ── Public entry point ──────────────────────────────────────────────

    /**
     * Adds plugin-specific fields (is_elite, elite, is_modeled, is_prop, model)
     * to the given Lua entity table. Safe to call regardless of which plugins
     * are installed.
     */
    static void addPluginFields(LuaTable table, Entity entity) {
        addEliteFields(table, entity);
        addFmmFields(table, entity);
    }

    // ── EliteMobs ───────────────────────────────────────────────────────

    private static void resolveElite() {
        if (eliteResolved) return;
        eliteResolved = true;

        if (!Bukkit.getPluginManager().isPluginEnabled("EliteMobs")) return;

        try {
            Class<?> trackerClass = Class.forName("com.magmaguy.elitemobs.entitytracker.EntityTracker");
            isEliteMobMethod = trackerClass.getMethod("isEliteMob", Entity.class);
            getEliteMobEntityMethod = trackerClass.getMethod("getEliteMobEntity", Entity.class);

            Class<?> eliteClass = Class.forName("com.magmaguy.elitemobs.mobconstructor.EliteEntity");
            eliteGetLevelMethod = eliteClass.getMethod("getLevel");
            eliteGetNameMethod = eliteClass.getMethod("getName");
            eliteGetHealthMethod = eliteClass.getMethod("getHealth");
            eliteGetMaxHealthMethod = eliteClass.getMethod("getMaxHealth");
            eliteIsCustomBossMethod = eliteClass.getMethod("isCustomBossEntity");

            Class<?> removalReasonClass = Class.forName("com.magmaguy.elitemobs.api.internal.RemovalReason");
            eliteRemoveMethod = eliteClass.getMethod("remove", removalReasonClass);
            removalReasonOther = Enum.valueOf(removalReasonClass.asSubclass(Enum.class), "OTHER");
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, "[MagmaCore] Failed to resolve EliteMobs API via reflection", e);
            isEliteMobMethod = null;
        }
    }

    private static void addEliteFields(LuaTable table, Entity entity) {
        resolveElite();

        if (isEliteMobMethod == null) {
            table.set("is_elite", LuaValue.FALSE);
            table.set("is_custom_boss", LuaValue.FALSE);
            return;
        }

        try {
            boolean isElite = (boolean) isEliteMobMethod.invoke(null, entity);
            table.set("is_elite", LuaValue.valueOf(isElite));

            if (!isElite) {
                table.set("is_custom_boss", LuaValue.FALSE);
                return;
            }

            Object eliteEntity = getEliteMobEntityMethod.invoke(null, entity);
            if (eliteEntity == null) return;

            LuaTable eliteTable = new LuaTable();
            eliteTable.set("level", LuaValue.valueOf((int) eliteGetLevelMethod.invoke(eliteEntity)));

            Object nameObj = eliteGetNameMethod.invoke(eliteEntity);
            eliteTable.set("name", nameObj != null ? LuaValue.valueOf(nameObj.toString()) : LuaValue.NIL);

            eliteTable.set("health", LuaValue.valueOf((double) eliteGetHealthMethod.invoke(eliteEntity)));
            eliteTable.set("max_health", LuaValue.valueOf((double) eliteGetMaxHealthMethod.invoke(eliteEntity)));
            boolean isCustomBoss = (boolean) eliteIsCustomBossMethod.invoke(eliteEntity);
            eliteTable.set("is_custom_boss", LuaValue.valueOf(isCustomBoss));
            table.set("is_custom_boss", LuaValue.valueOf(isCustomBoss));

            final Object capturedElite = eliteEntity;
            eliteTable.set("remove", LuaTableSupport.tableMethod(eliteTable, args -> {
                try {
                    eliteRemoveMethod.invoke(capturedElite, removalReasonOther);
                } catch (Exception ignored) {}
                return LuaValue.NIL;
            }));

            table.set("elite", eliteTable);
        } catch (Exception e) {
            table.set("is_elite", LuaValue.FALSE);
        }
    }

    // ── FreeMinecraftModels ─────────────────────────────────────────────

    private static void resolveFmm() {
        if (fmmResolved) return;
        fmmResolved = true;

        if (!Bukkit.getPluginManager().isPluginEnabled("FreeMinecraftModels")) return;

        try {
            // ModeledEntity
            modeledEntityClass = Class.forName("com.magmaguy.freeminecraftmodels.customentity.ModeledEntity");
            getLoadedMapMethod = modeledEntityClass.getMethod("getLoadedModeledEntitiesWithUnderlyingEntities");
            getEntityIDMethod = modeledEntityClass.getMethod("getEntityID");
            playAnimationMethod = modeledEntityClass.getMethod("playAnimation", String.class, boolean.class, boolean.class);
            stopAnimationsMethod = modeledEntityClass.getMethod("stopCurrentAnimations");
            modeledRemoveMethod = modeledEntityClass.getMethod("remove");

            // DynamicEntity
            Class<?> dynamicClass = Class.forName("com.magmaguy.freeminecraftmodels.customentity.DynamicEntity");
            isDynamicEntityMethod = dynamicClass.getMethod("isDynamicEntity", Entity.class);
            getDynamicEntityMethod = dynamicClass.getMethod("getDynamicEntity", Entity.class);

            // PropEntity
            propEntityClass = Class.forName("com.magmaguy.freeminecraftmodels.customentity.PropEntity");
            isPropEntityMethod = propEntityClass.getMethod("isPropEntity", org.bukkit.entity.ArmorStand.class);
            getPropEntityIDMethod = propEntityClass.getMethod("getPropEntityID", org.bukkit.entity.ArmorStand.class);
        } catch (Exception e) {
            Bukkit.getLogger().log(Level.WARNING, "[MagmaCore] Failed to resolve FreeMinecraftModels API via reflection", e);
            modeledEntityClass = null;
        }
    }

    private static void addFmmFields(LuaTable table, Entity entity) {
        resolveFmm();

        if (modeledEntityClass == null) {
            table.set("is_modeled", LuaValue.FALSE);
            table.set("is_prop", LuaValue.FALSE);
            return;
        }

        try {
            // Check modeled entity via the static map
            @SuppressWarnings("unchecked")
            HashMap<Entity, ?> loadedMap = (HashMap<Entity, ?>) getLoadedMapMethod.invoke(null);
            Object modeledEntity = loadedMap != null ? loadedMap.get(entity) : null;

            boolean isModeled = modeledEntity != null;
            table.set("is_modeled", LuaValue.valueOf(isModeled));

            // Check prop (only ArmorStands can be props)
            boolean isProp = false;
            if (entity instanceof org.bukkit.entity.ArmorStand armorStand) {
                try {
                    isProp = (boolean) isPropEntityMethod.invoke(null, armorStand);
                } catch (Exception ignored) {}
            }
            table.set("is_prop", LuaValue.valueOf(isProp));

            if (!isModeled) return;

            // Build model subtable
            LuaTable modelTable = new LuaTable();

            String modelId = (String) getEntityIDMethod.invoke(modeledEntity);
            modelTable.set("model_id", modelId != null ? LuaValue.valueOf(modelId) : LuaValue.NIL);

            final Object capturedModeledEntity = modeledEntity;

            modelTable.set("play_animation", LuaTableSupport.tableMethod(modelTable, args -> {
                String animName = args.checkjstring(1);
                boolean blend = args.optboolean(2, false);
                boolean loop = args.optboolean(3, false);
                try {
                    boolean result = (boolean) playAnimationMethod.invoke(capturedModeledEntity, animName, blend, loop);
                    return LuaValue.valueOf(result);
                } catch (Exception e) {
                    return LuaValue.FALSE;
                }
            }));

            modelTable.set("stop_animations", LuaTableSupport.tableMethod(modelTable, args -> {
                try {
                    stopAnimationsMethod.invoke(capturedModeledEntity);
                } catch (Exception ignored) {}
                return LuaValue.NIL;
            }));

            modelTable.set("remove", LuaTableSupport.tableMethod(modelTable, args -> {
                try {
                    modeledRemoveMethod.invoke(capturedModeledEntity);
                } catch (Exception ignored) {}
                return LuaValue.NIL;
            }));

            table.set("model", modelTable);
        } catch (Exception e) {
            table.set("is_modeled", LuaValue.FALSE);
            table.set("is_prop", LuaValue.FALSE);
        }
    }
}
