/**
 * Native 26.2 Mind implementation.
 *
 * <p>The carrier uses the requested vanilla living entity type without registering a custom
 * Mojang entity, memory, or sensor type. Minecraft therefore reloads it as that vanilla carrier
 * after a chunk or server reload. Callers must inspect entity-add events before wrapping the carrier, call
 * {@code MindHost.rehydrateBody}, reopen or find the logical session from the returned owner, and
 * then call {@code MindHandle.attachBody}. Rehydration attaches MagmaCore's external Mind session
 * to that exact factory-created vanilla carrier; it does not replace the entity or change its
 * physical UUID. The marker's logical owner remains stable. Marker ownership uses the host identity supplied when the Mind host is created;
 * a separately shaded MagmaCore host can identify the entity as a Mind carrier but cannot rehydrate
 * or clear it.</p>
 *
 * <p>Semantic action requests run synchronously inside the active Brain callback. A dedicated
 * ACTION lease and the program's per-entity-tick request cap guard the host sink. Navigation
 * diagnostics sample body movement once at the start of each native Mind tick. Reissuing a move
 * request does not erase stuck history.</p>
 */
package com.magmaguy.easyminecraftgoals.v26.mind;
