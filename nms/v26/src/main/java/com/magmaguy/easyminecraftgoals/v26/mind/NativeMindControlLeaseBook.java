package com.magmaguy.easyminecraftgoals.v26.mind;

import com.magmaguy.magmacore.ai.MindControl;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

final class NativeMindControlLeaseBook {
    private static final Comparator<NativeMindBehaviorControl> ORDER =
            Comparator.comparingInt(NativeMindBehaviorControl::priority)
                    .thenComparing(NativeMindBehaviorControl::identifier);

    private final EnumMap<MindControl, NativeMindBehaviorControl> owners =
            new EnumMap<>(MindControl.class);

    boolean acquire(NativeMindBehaviorControl requester, Set<MindControl> requested) {
        if (requested.isEmpty()) return true;

        Set<NativeMindBehaviorControl> displaced = new LinkedHashSet<>();
        for (MindControl control : requested) {
            NativeMindBehaviorControl current = owners.get(control);
            if (current == null || current == requester) continue;
            if (ORDER.compare(current, requester) <= 0) return false;
            displaced.add(current);
        }

        List<NativeMindBehaviorControl> ordered = new ArrayList<>(displaced);
        ordered.sort(ORDER);
        for (NativeMindBehaviorControl current : ordered) current.preempt();
        for (MindControl control : requested) owners.put(control, requester);
        return true;
    }

    void release(NativeMindBehaviorControl owner) {
        owners.entrySet().removeIf(entry -> entry.getValue() == owner);
    }

    boolean holds(NativeMindBehaviorControl owner, MindControl control) {
        return owners.get(control) == owner;
    }

    void clear() {
        owners.clear();
    }
}
