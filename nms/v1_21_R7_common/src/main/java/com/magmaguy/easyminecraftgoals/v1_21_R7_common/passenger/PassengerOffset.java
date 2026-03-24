package com.magmaguy.easyminecraftgoals.v1_21_R7_common.passenger;

import com.magmaguy.easyminecraftgoals.v1_21_R7_common.CraftBukkitBridge;
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
    private static Field dimensionsField = null;

    private PassengerOffset() {
    }

    public static boolean setPassengerOffset(Entity entity, double offsetX, double offsetY, double offsetZ) {
        try {
            if (dimensionsField == null) {
                dimensionsField = findDimensionsField();
            }
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

    private static Field findDimensionsField() throws NoSuchFieldException {
        String fieldName = CraftBukkitBridge.isPaper() ? "dimensions" : "bz";
        Field field = Entity.class.getDeclaredField(fieldName);
        field.setAccessible(true);
        return field;
    }
}
