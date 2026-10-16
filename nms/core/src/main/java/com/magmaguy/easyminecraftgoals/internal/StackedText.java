package com.magmaguy.easyminecraftgoals.internal;

import com.magmaguy.easyminecraftgoals.NMSManager;
import com.magmaguy.easyminecraftgoals.thirdparty.BedrockChecker;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Location;
import org.bukkit.entity.Player;

import java.util.*;

/**
 * Explicit-viewer nameplate built from FakeText rows, ordered top to bottom.
 * The bottom row stays anchored; extra rows grow upward. No scheduler or world entities are owned here.
 * Settings may be changed from the packet clock; syncViewers must run on the primary thread.
 */
public final class StackedText {
    private static final double TEXT_HEIGHT = 0.225;
    private final Map<UUID, Viewer> viewers = new HashMap<>();
    private List<String> lines = List.of();
    private float scale = 1;
    private double gap = 0.1;
    private boolean visible = true;
    private boolean removed;
    private Location anchor;
    // While riding, Java rows are packet passengers of the vehicle and carry their height above its seat in their
    // translation, so viewers place them from the same smoothed entity position as everything else riding it.
    private org.bukkit.entity.Entity vehicle;
    private double seatOffsetY;

    public synchronized void setLines(List<String> text) {
        List<String> normalized = new ArrayList<>();
        for (String block : text) {
            String carry = "";
            for (String line : Objects.requireNonNullElse(block, "").split("\\R", -1)) {
                String formatted = carry + line;
                normalized.add(formatted);
                carry = ChatColor.getLastColors(formatted);
            }
        }
        lines = List.copyOf(normalized);
    }

    public synchronized void setScale(float scale) {
        if (!Float.isFinite(scale) || scale <= 0) throw new IllegalArgumentException("Text scale must be positive and finite");
        this.scale = scale;
    }

    /** Clear space between rows, in blocks at text scale 1. */
    public synchronized void setLineGap(double gap) {
        if (!Double.isFinite(gap) || gap < 0) throw new IllegalArgumentException("Text line gap must be nonnegative and finite");
        this.gap = gap;
    }

    public synchronized void setVisible(boolean visible) {
        this.visible = visible;
        if (!visible) clearViewers();
    }

    public synchronized void move(Location anchor) {
        if (removed || anchor == null || anchor.getWorld() == null) return;
        if (this.anchor != null && !this.anchor.getWorld().equals(anchor.getWorld())) clearViewers();
        this.anchor = anchor.clone();
        for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) moveRows(entry.getKey(), entry.getValue());
    }

    /**
     * Rides {@code vehicle}: Java rows become its packet passengers at its next free seat and keep only their
     * height above that seat, so they move with the entity exactly as its riders do instead of chasing it by
     * teleports (the anchor's horizontal offset is dropped while riding). Null places the rows freely again.
     * Bedrock rows keep teleporting. Call on the primary thread; {@link #move} keeps the height current.
     */
    public synchronized void ride(org.bukkit.entity.Entity vehicle) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Text riding must change on the primary thread");
        if (vehicle != null && (!vehicle.isValid() || NMSManager.getAdapter() == null)) vehicle = null;
        if (vehicle != null) {
            org.bukkit.util.Vector seat = NMSManager.getAdapter().getPassengerSeatOffset(vehicle, vehicle.getPassengers().size());
            if (seat == null) vehicle = null;
            else seatOffsetY = seat.getY();
        }
        boolean changed = this.vehicle != vehicle && (this.vehicle == null || vehicle == null
                || this.vehicle.getEntityId() != vehicle.getEntityId());
        this.vehicle = vehicle;
        if (!changed) return;
        for (Map.Entry<UUID, Viewer> entry : viewers.entrySet()) {
            Viewer viewer = entry.getValue();
            if (viewer.bedrock || viewer.rows.size() != lines.size()) continue;
            for (int i = 0; i < viewer.rows.size(); i++) {
                FakeText row = viewer.rows.get(i);
                if (vehicle != null) row.mountTo(vehicle);
                else {
                    // A freed row is wherever its seat left it; put it back under its own position at once.
                    row.dismount();
                    row.setTranslation(0, 0, 0);
                    row.teleport(rowLocation(viewer, i));
                }
            }
            if (vehicle != null) moveRows(entry.getKey(), viewer);
        }
    }

    /** Reconciles membership and current text/style without recreating unchanged rows. */
    public synchronized void syncViewers(Collection<? extends Player> candidates) {
        if (!Bukkit.isPrimaryThread()) throw new IllegalStateException("Text viewers must be reconciled on the primary thread");
        if (removed || !visible || anchor == null || lines.isEmpty()
                || lines.stream().allMatch(line -> Objects.requireNonNullElse(ChatColor.stripColor(line), "").isBlank())) {
            clearViewers();
            return;
        }
        Set<UUID> wanted = new HashSet<>();
        for (Player player : candidates) {
            if (player == null || !player.isOnline() || !anchor.getWorld().equals(player.getWorld())) continue;
            UUID id = player.getUniqueId();
            wanted.add(id);
            boolean bedrock = BedrockChecker.isBedrock(player);
            // Native Bedrock name tags cannot be scaled. Keep their row spacing at native size too.
            float effectiveScale = bedrock ? 1 : scale;
            Viewer viewer = viewers.get(id);
            if (viewer != null && (viewer.bedrock != bedrock || viewer.scale != effectiveScale
                    || viewer.rows.size() != lines.size())) {
                viewer.remove();
                viewers.remove(id);
                viewer = null;
            }
            if (viewer == null) {
                viewer = new Viewer(bedrock, effectiveScale);
                try {
                    for (int i = 0; i < lines.size(); i++) {
                        FakeText row = NMSManager.getAdapter().createFakeText(rowLocation(viewer, i),
                                new FakeTextSettings().setText(lines.get(i)).setScale(effectiveScale)
                                        .setBackgroundArgb(0).setShadow(false).setLineWidth(Integer.MAX_VALUE));
                        viewer.rows.add(row);
                        row.displayTo(player);
                        if (vehicle != null && !bedrock) row.mountTo(vehicle);
                    }
                    viewers.put(id, viewer);
                } catch (RuntimeException | Error failure) {
                    viewer.remove();
                    throw failure;
                }
            } else {
                for (int i = 0; i < lines.size(); i++)
                    if (!Objects.equals(viewer.rows.get(i).getText(), lines.get(i))) viewer.rows.get(i).setText(lines.get(i));
            }
            moveRows(id, viewer);
        }
        Iterator<Map.Entry<UUID, Viewer>> iterator = viewers.entrySet().iterator();
        while (iterator.hasNext()) {
            var entry = iterator.next();
            if (!wanted.contains(entry.getKey())) {
                entry.getValue().remove();
                iterator.remove();
            }
        }
    }

    public synchronized void hideFrom(UUID id) {
        Viewer viewer = viewers.remove(id);
        if (viewer != null) viewer.remove();
    }

    public synchronized void remove() {
        removed = true;
        clearViewers();
        anchor = null;
    }

    private Location rowLocation(Viewer viewer, int index) {
        double up = (lines.size() - 1 - index) * (TEXT_HEIGHT + gap) * viewer.scale;
        // Marker armor-stand labels sit 0.5 blocks above their entity position.
        return anchor.clone().add(0, up - (viewer.bedrock ? 0.5 : 0), 0);
    }

    private void moveRows(UUID id, Viewer viewer) {
        // A text change can precede the next primary-thread reconciliation.
        if (viewer.rows.size() != lines.size()) return;
        org.bukkit.entity.Entity vehicle = this.vehicle;
        if (vehicle != null && !viewer.bedrock) {
            // Riders get no position of their own, so the row's height above the seat travels in its translation.
            // A centred billboard applies that translation in the camera's frame, where world-up is tilted back
            // by the viewer's pitch; without the tilt the text would float toward whoever looks down at it.
            Player player = Bukkit.getPlayer(id);
            double pitch = Math.toRadians(player == null ? 0 : Math.round(player.getLocation().getPitch() / 2f) * 2f);
            double seatY = vehicle.getLocation().getY() + seatOffsetY;
            for (int i = 0; i < viewer.rows.size(); i++) {
                double height = rowLocation(viewer, i).getY() - seatY;
                viewer.rows.get(i).setTranslation(0, (float) (height * Math.cos(pitch)), (float) (height * Math.sin(pitch)));
            }
            return;
        }
        for (int i = 0; i < viewer.rows.size(); i++) {
            Location target = rowLocation(viewer, i);
            Location previous = viewer.rows.get(i).getLocation();
            if (!previous.getWorld().equals(target.getWorld()) || previous.distanceSquared(target) > 1.0E-8)
                viewer.rows.get(i).teleport(target);
        }
    }

    private void clearViewers() {
        viewers.values().forEach(Viewer::remove);
        viewers.clear();
    }

    private static final class Viewer {
        private final boolean bedrock;
        private final float scale;
        private final List<FakeText> rows = new ArrayList<>();
        private Viewer(boolean bedrock, float scale) { this.bedrock = bedrock; this.scale = scale; }
        private void remove() { rows.forEach(FakeText::remove); }
    }
}
