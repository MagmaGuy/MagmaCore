package com.magmaguy.easyminecraftgoals.internal;

/**
 * Runtime tuning switches for the packet-entity layer, set by the consuming plugin
 * (e.g. FreeMinecraftModels) from its own config so the NMS modules stay config-free.
 */
public final class PacketEntityTuning {

    private PacketEntityTuning() {
    }

    /**
     * When true (default), the per-tick item-display metadata packet carries only the
     * entity-data values that changed since the last tick ({@code SynchedEntityData.packDirty()})
     * instead of the full non-default snapshot ({@code getNonDefaultValues()}).
     *
     * <p>The full snapshot re-serializes the entire item-display blob (item model +
     * custom-model-data) every tick even though only the bone's transformation changed,
     * which is the dominant per-client payload in dense modeled-entity areas. Sending only
     * the dirty transformation slashes that. New viewers still get the full snapshot on
     * spawn ({@code displayTo}) and the periodic resync re-sends everything, so deltas can
     * never leave a client permanently out of sync.</p>
     *
     * <p>Set to false to restore the old always-full-snapshot behavior (A/B comparison or
     * rollback).</p>
     */
    public static volatile boolean useDeltaMetadataUpdates = true;
}
