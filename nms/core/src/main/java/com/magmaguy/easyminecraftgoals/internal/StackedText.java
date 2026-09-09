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
        for (Viewer viewer : viewers.values()) moveRows(viewer);
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
            moveRows(viewer);
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

    private void moveRows(Viewer viewer) {
        // A text change can precede the next primary-thread reconciliation.
        if (viewer.rows.size() != lines.size()) return;
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
