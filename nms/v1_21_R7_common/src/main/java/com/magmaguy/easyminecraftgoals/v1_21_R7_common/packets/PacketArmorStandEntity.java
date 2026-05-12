package com.magmaguy.easyminecraftgoals.v1_21_R7_common.packets;

import com.magmaguy.easyminecraftgoals.internal.AbstractPacketBundle;
import com.magmaguy.easyminecraftgoals.internal.PacketModelEntity;
import com.magmaguy.easyminecraftgoals.internal.PacketTextEntity;
import com.magmaguy.easyminecraftgoals.v1_21_R7_common.CraftBukkitBridge;
import com.mojang.datafixers.util.Pair;
import net.minecraft.core.Rotations;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.world.entity.EntityType;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.entity.decoration.ArmorStand;
import net.minecraft.world.item.ItemStack;
import org.bukkit.*;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.LeatherArmorMeta;
import org.bukkit.util.EulerAngle;

import java.util.List;
import java.util.UUID;

public class PacketArmorStandEntity extends AbstractPacketEntity<ArmorStand> implements PacketModelEntity, PacketTextEntity {

    private ItemStack nmsLeatherHorseArmor;
    private org.bukkit.inventory.ItemStack leatherHorseArmor;
    private ArmorStand armorStand;

    public PacketArmorStandEntity(Location location) {
        super(location);
    }

    @Override
    protected ArmorStand createEntity(Location location) {
        //This doesn't create a real entity until it gets added to the world, which for packet entity purposes is never
        return new ArmorStand(EntityType.ARMOR_STAND, getNMSLevel(location));
    }

    public void initializeModel(Location location, String modelID) {
        armorStand = entity;
        armorStand.setInvisible(true);
        armorStand.setMarker(true);
        leatherHorseArmor = new org.bukkit.inventory.ItemStack(Material.LEATHER_HORSE_ARMOR);
        LeatherArmorMeta itemMeta = (LeatherArmorMeta) leatherHorseArmor.getItemMeta();
        itemMeta.setItemModel(NamespacedKey.fromString(modelID));
        itemMeta.setColor(Color.WHITE);
        leatherHorseArmor.setItemMeta(itemMeta);

        nmsLeatherHorseArmor = CraftBukkitBridge.asNMSCopy(leatherHorseArmor);
        //Actually useless, managed by the packet that sends equipment info
        armorStand.setItemSlot(EquipmentSlot.HEAD, nmsLeatherHorseArmor);
    }

    @Override
    public void initializeText(Location location) {
        armorStand = entity;
        armorStand.setInvisible(true);
        armorStand.setMarker(true);
    }

    @Override
    public void setScale(float scale) {
        //Actually not possible for armor stands, sorry
    }

    @Override
    public void setHorseLeatherArmorColor(Color color) {
        LeatherArmorMeta leatherArmorMeta = (LeatherArmorMeta) leatherHorseArmor.getItemMeta();
        leatherArmorMeta.setColor(color);
        leatherHorseArmor.setItemMeta(leatherArmorMeta);
        nmsLeatherHorseArmor = CraftBukkitBridge.asNMSCopy(leatherHorseArmor);
        armorStand.setItemSlot(EquipmentSlot.HEAD, nmsLeatherHorseArmor);
    }

    @Override
    public void sendLocationAndRotationPacket(Location location, EulerAngle eulerAngle) {
        move(location);
        rotate(eulerAngle);
    }

    @Override
    public void sendLocationAndRotationAndScalePacket(Location location, EulerAngle eulerAngle, float scale) {
        sendLocationAndRotationPacket(location, eulerAngle);
    }

    @Override
    public AbstractPacketBundle generateLocationAndRotationAndScalePackets(AbstractPacketBundle packetBundle, Location location, EulerAngle eulerAngle, float scale) {
        packetBundle.addPacket(generateMovePacket(location), getViewersAsPlayers());
        if (eulerAngle != null) {
            Rotations rotations = new Rotations(
                    (float) Math.toDegrees(eulerAngle.getX()),
                    (float) Math.toDegrees(eulerAngle.getY()),
                    (float) Math.toDegrees(eulerAngle.getZ()));
            entity.setHeadPose(rotations);
            // Force-send the HeadPose data value every tick. createEntityDataPacket() uses
            // getNonDefaultValues() which silently drops the entry when the value equals the
            // field default OR when SynchedEntityData.set() didn't mark dirty -- either case
            // breaks bone-rotation animation on Bedrock. Manually packaging the field value
            // bypasses both filters so Geyser's ArmorStandEntity.setHeadRotation always fires.
            SynchedEntityData.DataValue<Rotations> dv =
                    SynchedEntityData.DataValue.create(ArmorStand.DATA_HEAD_POSE, rotations);
            packetBundle.addPacket(new ClientboundSetEntityDataPacket(entity.getId(), java.util.List.of(dv)), getViewersAsPlayers());
        }
        return packetBundle;
    }

    @Override
    public void displayTo(Player player) {
        super.displayTo(player);
        if (nmsLeatherHorseArmor != null) {
            sendPacket(player, new ClientboundSetEquipmentPacket(entity.getId(), List.of(Pair.of(EquipmentSlot.HEAD, nmsLeatherHorseArmor))));
        }
    }

    public void displayTo(UUID player) {
        displayTo(Bukkit.getPlayer(player));
    }

    @Override
    public void addViewer(UUID player) {
        super.addViewer(player);
        displayTo(player);
    }

    private void rotate(EulerAngle eulerAngle) {
        if (eulerAngle == null) return;
        Rotations rotations = new Rotations(
                (float) Math.toDegrees(eulerAngle.getX()),
                (float) Math.toDegrees(eulerAngle.getY()),
                (float) Math.toDegrees(eulerAngle.getZ()));
        entity.setHeadPose(rotations);
        // Force-send the HeadPose data value every tick. The default createEntityDataPacket()
        // uses getNonDefaultValues() which drops the entry when the value equals the field default
        // OR when SynchedEntityData.set() didn't mark dirty (because the new value equals the
        // previously-set value). Either case prevents Geyser from receiving HeadPose updates and
        // breaks bone-rotation animation on Bedrock. Manually packaging the field value bypasses
        // both filters.
        SynchedEntityData.DataValue<Rotations> dv =
                SynchedEntityData.DataValue.create(ArmorStand.DATA_HEAD_POSE, rotations);
        sendPacket(new ClientboundSetEntityDataPacket(entity.getId(), java.util.List.of(dv)));
    }

    @Override
    public void setText(String text) {
        armorStand.setCustomNameVisible(true);
        armorStand.setCustomName(CraftBukkitBridge.fromLegacyText(text));
        sendPacket(createEntityDataPacket());
    }

    @Override
    public void setTextVisible(boolean visible) {
        armorStand.setCustomNameVisible(visible);
        sendPacket(createEntityDataPacket());
    }
}
