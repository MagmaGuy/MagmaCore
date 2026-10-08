package com.magmaguy.magmacore.rails;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.block.data.Rail;
import org.bukkit.entity.EntityType;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerTeleportEvent;
import org.bukkit.event.player.PlayerToggleSneakEvent;
import org.bukkit.plugin.Plugin;

/**
 * Seats a player in a packet-only minecart that drives itself along connected rails, starting in the
 * direction the player faces. Sneaking, the end of the track or anything else moving the player gets
 * them off, and the minecart disappears with the ride. The owner calls {@link #tick()} every tick.
 */
public final class RailRide implements Listener {
    /** A vanilla minecart on a flat rail sits this far above its cell. */
    private static final double CART_HEIGHT = 0.0625;
    private final Player rider;
    private final World world;
    private final RailTrack track;
    private final PacketSeat seat;
    private final double speed;
    private Location position;
    private boolean closed;

    private RailRide(Player rider, RailTrack track, PacketSeat seat, double speed) {
        this.rider = rider;
        this.world = rider.getWorld();
        this.track = track;
        this.seat = seat;
        this.speed = speed;
    }

    /**
     * Starts a ride when the player stands on a rail. Returns null when there is no rail under the
     * player or this server's NMS adapter cannot seat players on packet vehicles.
     *
     * @param speed blocks per tick along the track
     */
    public static RailRide start(Plugin owner, Player rider, double speed) {
        if (!(speed > 0 && speed <= 2)) throw new IllegalArgumentException("Rail speed must be above 0 and at most 2 blocks per tick");
        if (!rider.isValid() || rider.isDead() || rider.isInsideVehicle()) return null;
        World world = rider.getWorld();
        Location at = rider.getLocation();
        double yaw = Math.toRadians(at.getYaw());
        RailTrack track = RailTrack.start((x, y, z) -> {
            if (y < world.getMinHeight() || y >= world.getMaxHeight() || !world.isChunkLoaded(x >> 4, z >> 4)) return null;
            return world.getBlockAt(x, y, z).getBlockData() instanceof Rail rail ? rail.getShape() : null;
        }, at.getX(), at.getY(), at.getZ(), -Math.sin(yaw), Math.cos(yaw));
        if (track == null) return null;
        Location start = cartLocation(world, track);
        PacketSeat seat = PacketSeats.open(rider, EntityType.MINECART, start);
        if (seat == null) return null;
        RailRide ride = new RailRide(rider, track, seat, speed);
        ride.position = start;
        seat.move(start);
        Bukkit.getPluginManager().registerEvents(ride, owner);
        return ride;
    }

    /** Advances the minecart by one tick. Returns false once the ride is over. */
    public boolean tick() {
        if (closed) return false;
        // Nothing but the ride moves a seated rider; any other movement means something took over.
        if (!rider.isOnline() || !rider.isValid() || rider.isDead() || rider.isInsideVehicle()
                || !rider.getWorld().equals(world) || rider.getLocation().distanceSquared(position) > 1) {
            close();
            return false;
        }
        boolean onTrack = track.advance(speed);
        position = cartLocation(world, track);
        seat.move(position);
        if (!onTrack) close();
        return onTrack;
    }

    /** Gets the rider off where the minecart is. Idempotent. */
    public void close() {
        finish(true);
    }

    private void finish(boolean placeRider) {
        if (closed) return;
        closed = true;
        HandlerList.unregisterAll(this);
        seat.close();
        if (!placeRider || !rider.isOnline() || !rider.isValid() || rider.isDead() || !rider.getWorld().equals(world)) return;
        // The client resumes from its seat; one teleport puts client and server on the same spot.
        Location landing = position.clone();
        landing.setYaw(rider.getLocation().getYaw());
        landing.setPitch(rider.getLocation().getPitch());
        rider.setFallDistance(0);
        rider.teleport(landing);
    }

    private static Location cartLocation(World world, RailTrack track) {
        Location location = new Location(world, track.x(), track.y() + CART_HEIGHT, track.z());
        location.setDirection(new org.bukkit.util.Vector(track.directionX(), 0, track.directionZ()));
        return location;
    }

    @EventHandler(priority = EventPriority.LOW, ignoreCancelled = true)
    public void sneak(PlayerToggleSneakEvent event) {
        if (event.isSneaking() && event.getPlayer().equals(rider)) close();
    }

    /** The teleport itself places the rider; the minecart only has to go before it lands. */
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void teleport(PlayerTeleportEvent event) {
        if (event.getPlayer().equals(rider)) finish(false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void quit(PlayerQuitEvent event) {
        if (event.getPlayer().equals(rider)) finish(false);
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void death(PlayerDeathEvent event) {
        if (event.getEntity().equals(rider)) finish(false);
    }
}
