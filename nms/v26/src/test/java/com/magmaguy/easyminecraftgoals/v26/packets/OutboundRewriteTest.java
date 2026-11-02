package com.magmaguy.easyminecraftgoals.v26.packets;

import com.magmaguy.easyminecraftgoals.internal.PacketEquipmentVeil;
import com.magmaguy.easyminecraftgoals.internal.PacketPassengerRegistry;
import com.mojang.datafixers.util.Pair;
import net.minecraft.SharedConstants;
import net.minecraft.network.protocol.Packet;
import net.minecraft.network.protocol.game.ClientGamePacketListener;
import net.minecraft.network.protocol.game.ClientboundAddEntityPacket;
import net.minecraft.network.protocol.game.ClientboundBundlePacket;
import net.minecraft.network.protocol.game.ClientboundMoveEntityPacket;
import net.minecraft.network.protocol.game.ClientboundSetEntityDataPacket;
import net.minecraft.network.protocol.game.ClientboundSetEquipmentPacket;
import net.minecraft.network.protocol.game.ClientboundSetPassengersPacket;
import net.minecraft.network.syncher.EntityDataSerializers;
import net.minecraft.network.syncher.SynchedEntityData;
import net.minecraft.server.Bootstrap;
import net.minecraft.world.entity.EquipmentSlot;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.Vec3;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The outbound veil and passenger rewrites: untouched packets pass as they are, rewritten ones as before. */
class OutboundRewriteTest {
    private static final int BODY = 500;
    private static final int BONE = 900;
    private final UUID viewer = UUID.randomUUID();

    @BeforeAll
    static void bootstrap() {
        SharedConstants.tryDetectVersion();
        Bootstrap.bootStrap();
    }

    @AfterEach
    void clear() {
        PacketEquipmentVeil.clear();
        PacketPassengerRegistry.clear();
    }

    @Test
    void aBundleWithNothingToVeilPassesAsItIs() {
        PacketEquipmentVeil.veil(viewer, BODY);
        ClientboundBundlePacket bundle = bundle(flags(BONE, (byte) 0), flags(BONE + 1, (byte) 0x40), add(BONE + 2));
        assertSame(bundle, EquipmentPackets.veil(viewer, bundle));
        assertSame(bundle, EquipmentPackets.veil(UUID.randomUUID(), bundle), "a viewer without veils");
    }

    @Test
    void aBundleVeilsItsBodyAndKeepsEverythingElse() {
        PacketEquipmentVeil.veil(viewer, BODY);
        Packet<ClientGamePacketListener> bone = flags(BONE, (byte) 0x40);
        Packet<ClientGamePacketListener> spawn = add(BODY);
        Packet<ClientGamePacketListener> glowing = flags(BODY, (byte) 0x40);
        Packet<ClientGamePacketListener> invisible = flags(BODY, (byte) 0x20);
        ClientboundSetEquipmentPacket equipment = new ClientboundSetEquipmentPacket(BODY,
                // Items need bound data components, which a unit test has none of; the veil rewrites any slot.
                List.of(Pair.of(EquipmentSlot.MAINHAND, ItemStack.EMPTY)));

        Packet<?> veiled = EquipmentPackets.veil(viewer, bundle(bone, spawn, glowing, invisible, equipment));

        List<Packet<?>> children = children(veiled);
        assertEquals(6, children.size());
        assertSame(bone, children.get(0));
        assertSame(spawn, children.get(1));
        assertFlags(children.get(2), BODY, (byte) 0x20);
        assertFlags(children.get(3), BODY, (byte) 0x20);
        assertSame(invisible, children.get(4), "already invisible and not glowing");
        ClientboundSetEquipmentPacket emptied = assertInstanceOf(ClientboundSetEquipmentPacket.class, children.get(5));
        assertNotSame(equipment, emptied);
        assertEquals(BODY, emptied.getEntity());
        assertEquals(EquipmentSlot.MAINHAND, emptied.getSlots().get(0).getFirst());
        assertSame(ItemStack.EMPTY, emptied.getSlots().get(0).getSecond());
    }

    @Test
    void aLoneVeiledSpawnTravelsWithItsFlags() {
        PacketEquipmentVeil.veil(viewer, BODY);
        Packet<ClientGamePacketListener> spawn = add(BODY);
        List<Packet<?>> children = children(EquipmentPackets.veil(viewer, spawn));
        assertEquals(2, children.size());
        assertSame(spawn, children.get(0));
        assertFlags(children.get(1), BODY, (byte) 0x20);
        Packet<ClientGamePacketListener> other = add(BONE);
        assertSame(other, EquipmentPackets.veil(viewer, other));
    }

    @Test
    void aMountWithoutSyntheticPassengersPassesAsItIs() {
        PacketPassengerRegistry.attach(viewer, BODY + 1, BONE, 0, 0);
        ClientboundSetPassengersPacket mount = PassengerPackets.create(BODY, new int[]{7});
        assertSame(mount, PassengerPackets.compose(viewer, mount));
        ClientboundBundlePacket bundle = bundle(flags(BONE, (byte) 0), mount);
        assertSame(bundle, PassengerPackets.compose(viewer, bundle));
    }

    @Test
    void aMountGetsItsSyntheticPassengersAndTheirRotations() {
        PacketPassengerRegistry.attach(viewer, BODY, BONE, 90, 0);
        PacketPassengerRegistry.attach(viewer, BODY, BONE + 1, 180, 0);
        Packet<ClientGamePacketListener> pose = flags(BONE, (byte) 0);
        ClientboundBundlePacket bundle = bundle(pose, PassengerPackets.create(BODY, new int[]{7}));

        List<Packet<?>> children = children(PassengerPackets.compose(viewer, bundle));

        assertEquals(4, children.size());
        assertSame(pose, children.get(0));
        ClientboundSetPassengersPacket mount = assertInstanceOf(ClientboundSetPassengersPacket.class, children.get(1));
        assertArrayEquals(new int[]{7, BONE, BONE + 1}, mount.getPassengers());
        ClientboundMoveEntityPacket.Rot first = assertInstanceOf(ClientboundMoveEntityPacket.Rot.class, children.get(2));
        assertEquals(90F, first.getYRot(), 1.5F);
        assertInstanceOf(ClientboundMoveEntityPacket.Rot.class, children.get(3));
    }

    @Test
    void aVehicleSeatedAgainLaterInABundleTurnsItsPassengersBackOnlyAfterTheLastList() {
        PacketPassengerRegistry.attach(viewer, BODY, BONE, 90, 0);
        PacketPassengerRegistry.attach(viewer, BODY, BONE + 1, 180, 0);
        Packet<ClientGamePacketListener> pose = flags(BONE + 1, (byte) 0);
        ClientboundBundlePacket bundle = bundle(
                PassengerPackets.create(BODY, new int[]{7}), pose, PassengerPackets.create(BODY, new int[]{7}));

        List<Packet<?>> children = children(PassengerPackets.compose(viewer, bundle));

        assertEquals(5, children.size());
        ClientboundSetPassengersPacket first = assertInstanceOf(ClientboundSetPassengersPacket.class, children.get(0));
        assertArrayEquals(new int[]{7, BONE, BONE + 1}, first.getPassengers());
        assertSame(pose, children.get(1));
        ClientboundSetPassengersPacket last = assertInstanceOf(ClientboundSetPassengersPacket.class, children.get(2));
        assertArrayEquals(new int[]{7, BONE, BONE + 1}, last.getPassengers());
        ClientboundMoveEntityPacket.Rot turn = assertInstanceOf(ClientboundMoveEntityPacket.Rot.class, children.get(3));
        assertEquals(90F, turn.getYRot(), 1.5F);
        assertInstanceOf(ClientboundMoveEntityPacket.Rot.class, children.get(4));
    }

    @Test
    void eachVehicleSeatedOnceInABundleKeepsItsRotations() {
        PacketPassengerRegistry.attach(viewer, BODY, BONE, 90, 0);
        PacketPassengerRegistry.attach(viewer, BODY + 1, BONE + 1, 90, 0);
        ClientboundBundlePacket bundle = bundle(
                PassengerPackets.create(BODY, new int[0]), PassengerPackets.create(BODY + 1, new int[0]));

        List<Packet<?>> children = children(PassengerPackets.compose(viewer, bundle));

        assertEquals(4, children.size());
        assertArrayEquals(new int[]{BONE}, assertInstanceOf(ClientboundSetPassengersPacket.class, children.get(0)).getPassengers());
        assertInstanceOf(ClientboundMoveEntityPacket.Rot.class, children.get(1));
        assertArrayEquals(new int[]{BONE + 1}, assertInstanceOf(ClientboundSetPassengersPacket.class, children.get(2)).getPassengers());
        assertInstanceOf(ClientboundMoveEntityPacket.Rot.class, children.get(3));
    }

    @Test
    void bonesLeavingTogetherInABundlePassAsTheyAreAndTurnNothingBack() {
        // The viewer still sees another model's bone, so the rewrite looks at this bundle at all.
        PacketPassengerRegistry.attach(viewer, BODY + 1, BONE + 2, 90, 0);
        // Each bone's dismount lists the bones still seated when it was made; all are gone by the time it is sent.
        ClientboundBundlePacket bundle = bundle(
                PassengerPackets.create(BODY, new int[]{7, BONE + 1}), PassengerPackets.create(BODY, new int[]{7}));

        assertSame(bundle, PassengerPackets.compose(viewer, bundle));
    }

    private static void assertFlags(Packet<?> packet, int id, byte flags) {
        ClientboundSetEntityDataPacket data = assertInstanceOf(ClientboundSetEntityDataPacket.class, packet);
        assertEquals(id, data.id());
        assertEquals(1, data.packedItems().size());
        assertEquals(0, data.packedItems().get(0).id());
        assertEquals(flags, data.packedItems().get(0).value());
    }

    private static List<Packet<?>> children(Packet<?> packet) {
        ClientboundBundlePacket bundle = assertInstanceOf(ClientboundBundlePacket.class, packet);
        List<Packet<?>> children = new ArrayList<>();
        bundle.subPackets().forEach(children::add);
        assertTrue(children.size() > 0);
        return children;
    }

    @SafeVarargs
    private static ClientboundBundlePacket bundle(Packet<? super ClientGamePacketListener>... packets) {
        return new ClientboundBundlePacket(List.of(packets));
    }

    private static Packet<ClientGamePacketListener> flags(int id, byte flags) {
        return new ClientboundSetEntityDataPacket(id, List.of(new SynchedEntityData.DataValue<>(0, EntityDataSerializers.BYTE, flags)));
    }

    private static Packet<ClientGamePacketListener> add(int id) {
        return new ClientboundAddEntityPacket(id, UUID.randomUUID(), 0, 64, 0, 0, 0, PacketEntityTypes.ARMOR_STAND, 0, Vec3.ZERO, 0);
    }
}
