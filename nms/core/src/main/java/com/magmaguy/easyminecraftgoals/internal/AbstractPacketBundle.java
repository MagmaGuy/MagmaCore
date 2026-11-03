package com.magmaguy.easyminecraftgoals.internal;

import org.bukkit.entity.Player;

import java.util.List;

public interface AbstractPacketBundle {
     void addPacket(Object packet, List<Player> viewers) ;

     /**
      * Adds a pose: a state that the entity's next pose replaces. A bundle that carries poses to a player tells
      * {@link PoseBacklog} when the connection has written it, so a player whose connection is behind skips poses.
      */
     default void addPosePacket(Object packet, List<Player> viewers) {
          addPacket(packet, viewers);
     }

     void send() ;
}
