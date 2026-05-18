package com.magmaguy.easyminecraftgoals.v26.passenger;

import net.minecraft.world.entity.Entity;
import net.minecraft.world.entity.EntityAttachment;
import net.minecraft.world.entity.EntityAttachments;
import net.minecraft.world.entity.EntityDimensions;
import net.minecraft.world.phys.Vec3;

import java.lang.reflect.Field;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class PassengerOffset {
    // Cached field reference + lookup-was-attempted flag so a single mapping
    // miss doesn't reprint the same stack trace once per spawn.
    private static Field dimensionsField = null;
    private static boolean dimensionsFieldLookupTried = false;

    private PassengerOffset() {
    }

    public static boolean setPassengerOffset(Entity entity, double offsetX, double offsetY, double offsetZ) {
        if (!dimensionsFieldLookupTried) {
            dimensionsFieldLookupTried = true;
            dimensionsField = findDimensionsField();
        }
        if (dimensionsField == null) return false;
        try {
            EntityDimensions currentDimensions = (EntityDimensions) dimensionsField.get(entity);

            EntityAttachments currentAttachments = currentDimensions.attachments();
            if (currentAttachments == null) {
                currentAttachments = EntityAttachments.createDefault(currentDimensions.width(), currentDimensions.height());
            }

            Field mapField = EntityAttachments.class.getDeclaredField("attachments");
            mapField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<EntityAttachment, List<Vec3>> existingMap = (Map<EntityAttachment, List<Vec3>>) mapField.get(currentAttachments);

            HashMap<EntityAttachment, List<Vec3>> newMap = new HashMap<>(existingMap);
            newMap.put(EntityAttachment.PASSENGER, List.of(new Vec3(offsetX, offsetY, offsetZ)));

            var ctor = EntityAttachments.class.getDeclaredConstructor(Map.class);
            ctor.setAccessible(true);
            EntityAttachments newAttachments = (EntityAttachments) ctor.newInstance(newMap);

            EntityDimensions newDimensions = new EntityDimensions(
                    currentDimensions.width(),
                    currentDimensions.height(),
                    currentDimensions.eyeHeight(),
                    newAttachments,
                    currentDimensions.fixed()
            );

            dimensionsField.set(entity, newDimensions);
            return true;
        } catch (Exception e) {
            e.printStackTrace();
            return false;
        }
    }

    /**
     * MC 26.1+ is fully unobfuscated on both Paper and Spigot, so find the
     * {@code EntityDimensions} field by type — survives field renames and
     * works on any 26+ fork. Returns null on failure so the caller can
     * record that and stop retrying.
     */
    private static Field findDimensionsField() {
        for (Field f : Entity.class.getDeclaredFields()) {
            if (f.getType() == EntityDimensions.class) {
                f.setAccessible(true);
                return f;
            }
        }
        new RuntimeException("PassengerOffset: no EntityDimensions field on Entity.class; passenger offsets disabled this session").printStackTrace();
        return null;
    }
}
