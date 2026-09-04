package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.inventory.EquipmentSlot;
import org.checkerframework.checker.nullness.qual.Nullable;

import java.util.Objects;

/**
 * The interaction semantics carried by a client entity-use packet.
 *
 * <p>Attack packets do not carry a hand, so {@link #hand()} is {@code null} for
 * {@link Action#ATTACK}. Both interaction actions always carry either
 * {@link EquipmentSlot#HAND} or {@link EquipmentSlot#OFF_HAND}.</p>
 */
public record PacketInteractionContext(Action action, @Nullable EquipmentSlot hand) {

    public PacketInteractionContext {
        Objects.requireNonNull(action, "action");
        if (action == Action.ATTACK) {
            if (hand != null) {
                throw new IllegalArgumentException("Attack packets do not carry a hand");
            }
        } else if (hand != EquipmentSlot.HAND && hand != EquipmentSlot.OFF_HAND) {
            throw new IllegalArgumentException("Interaction packets require a main or off hand");
        }
    }

    public static PacketInteractionContext attack() {
        return new PacketInteractionContext(Action.ATTACK, null);
    }

    public static PacketInteractionContext interact(EquipmentSlot hand) {
        return new PacketInteractionContext(Action.INTERACT, hand);
    }

    public static PacketInteractionContext interactAt(EquipmentSlot hand) {
        return new PacketInteractionContext(Action.INTERACT_AT, hand);
    }

    public boolean isAttack() {
        return action == Action.ATTACK;
    }

    public enum Action {
        ATTACK,
        INTERACT,
        INTERACT_AT
    }
}
