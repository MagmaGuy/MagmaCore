package com.magmaguy.magmacore.ai.overlay;

import com.magmaguy.magmacore.ai.MindContext;
import com.magmaguy.magmacore.scripting.LuaMindContextExtension;
import com.magmaguy.magmacore.scripting.tables.LuaLivingEntityTable;
import com.magmaguy.magmacore.scripting.tables.LuaTableSupport;
import org.bukkit.entity.LivingEntity;
import org.luaj.vm2.LuaString;
import org.luaj.vm2.LuaTable;
import org.luaj.vm2.LuaValue;
import org.luaj.vm2.Varargs;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What an overlay's modules see besides the Mind context every program gets: {@code c.overlay}, the mob's place in
 * the overlay, and {@code c.settings}, the calling module's settings for this mob.
 *
 * <p>{@code c.overlay} has {@code owner}, {@code owner_attacker}, {@code owner_target} and {@code attacker}, each an
 * entity table or nil and read afresh every time; {@code remaining}, the ticks the overlay has left, nil when it has
 * no end; {@code nearest(radius, kind)}; {@code entity(uuid)}, the living entity with that id while it is alive in
 * the mob's world, for a module that remembers whom it is after; and {@code finish()}, which ends the overlay once
 * the tick is over.</p>
 */
final class MindOverlayLua implements LuaMindContextExtension {
    private static final LuaString OVERLAY = LuaValue.valueOf("overlay"), SETTINGS = LuaValue.valueOf("settings");

    private final LuaTable overlayTable;
    private final Map<String, LuaTable> settings = new HashMap<>();

    MindOverlayLua(MindOverlays service, MindOverlays.Overlay overlay, Map<String, Map<String, Object>> settings) {
        settings.forEach((module, values) -> this.settings.put(module, MindOverlaySettings.toLua(values)));
        LuaTable table = new LuaTable();
        LuaTableSupport.lazyField(table, "owner", () -> entity(service.owner(overlay)));
        LuaTableSupport.lazyField(table, "owner_attacker", () -> entity(service.ownerAttacker(overlay)));
        LuaTableSupport.lazyField(table, "owner_target", () -> entity(service.ownerTarget(overlay)));
        LuaTableSupport.lazyField(table, "attacker", () -> entity(service.attacker(overlay)));
        LuaTableSupport.lazyField(table, "remaining", () -> {
            Long remaining = service.remaining(overlay);
            return remaining == null ? LuaValue.NIL : LuaValue.valueOf((double) remaining);
        });
        table.set("nearest", LuaTableSupport.tableMethod(table, args ->
                entity(service.nearest(overlay, args.checkdouble(1), args.optjstring(2, "living")))));
        table.set("entity", LuaTableSupport.tableMethod(table, args -> {
            try {
                return entity(service.present(overlay, UUID.fromString(args.checkjstring(1))));
            } catch (IllegalArgumentException notAnId) {
                return LuaValue.NIL;
            }
        }));
        table.set("finish", LuaTableSupport.tableMethod(table, args -> {
            overlay.finished = true;
            return LuaValue.NIL;
        }));
        overlayTable = table;
    }

    @Override
    public void extend(LuaTable table, MindContext context, String contribution) {
        table.rawset(OVERLAY, overlayTable);
        LuaTable own = settings.get(contribution);
        if (own != null) table.rawset(SETTINGS, own);
    }

    private static LuaValue entity(LivingEntity living) {
        return living == null ? LuaValue.NIL : LuaLivingEntityTable.build(living);
    }

    /**
     * The request a script makes with {@code mob:override_ai(behaviors, options)}: one behavior name or a list of
     * them, then an optional table with {@code duration} in ticks, {@code owner} as an entity table or a uuid, and
     * {@code settings} as a table of setting tables per behavior name.
     */
    static MindOverlayRequest request(Varargs args) {
        List<String> behaviors = new ArrayList<>();
        LuaValue named = args.arg1();
        if (named.type() == LuaValue.TSTRING) {
            behaviors.add(named.tojstring());
        } else {
            LuaTable list = named.checktable();
            for (int index = 1; index <= list.rawlen(); index++) behaviors.add(list.rawget(index).checkjstring());
        }
        if (args.narg() < 2 || args.arg(2).isnil()) return MindOverlayRequest.of(behaviors);
        LuaTable options = args.checktable(2);
        return new MindOverlayRequest(
                behaviors,
                owner(options.get("owner")),
                Math.max(0L, options.get("duration").optlong(0L)),
                settings(options.get("settings")));
    }

    private static UUID owner(LuaValue value) {
        if (value.isnil()) return null;
        LuaValue id = value.istable() ? value.checktable().get("uuid") : value;
        return UUID.fromString(id.checkjstring());
    }

    private static Map<String, Map<String, Object>> settings(LuaValue value) {
        Map<String, Map<String, Object>> settings = new LinkedHashMap<>();
        if (value.isnil()) return settings;
        LuaTable modules = value.checktable();
        LuaValue module = LuaValue.NIL;
        while (true) {
            Varargs next = modules.next(module);
            module = next.arg1();
            if (module.isnil()) break;
            LuaTable given = next.arg(2).checktable();
            Map<String, Object> values = new LinkedHashMap<>();
            LuaValue key = LuaValue.NIL;
            while (true) {
                Varargs entry = given.next(key);
                key = entry.arg1();
                if (key.isnil()) break;
                values.put(key.checkjstring(), plain(entry.arg(2)));
            }
            settings.put(module.checkjstring(), values);
        }
        return settings;
    }

    /** A setting as Java holds it: a number, a boolean, text, or a list of text. */
    private static Object plain(LuaValue value) {
        if (value.isboolean()) return value.toboolean();
        if (value.type() == LuaValue.TNUMBER) return value.todouble();
        if (value.istable()) {
            LuaTable list = value.checktable();
            List<String> texts = new ArrayList<>();
            for (int index = 1; index <= list.rawlen(); index++) texts.add(list.rawget(index).tojstring());
            return texts;
        }
        return value.tojstring();
    }
}
