package com.magmaguy.magmacore.ai;

/**
 * A gentle rise and dip an amphibious body swims along while it travels through water, as a penguin or a seal
 * porpoises: its height swings {@code height} blocks from crest to trough once every {@code periodTicks}, on top of
 * the height its path asks for. The wave builds with the body's swimming speed and fades as it slows, so a body
 * resting in water holds its depth. {@link #NONE} swims level.
 *
 * @param height      blocks from crest to trough, 0 to 4
 * @param periodTicks ticks per wave, 10 or more
 */
public record MindSwimWave(double height, int periodTicks) {
    /** No wave: the body swims level along its path. */
    public static final MindSwimWave NONE = new MindSwimWave(0D, 40);
    public static final double MAX_HEIGHT = 4D;
    public static final int MIN_PERIOD_TICKS = 10;

    public MindSwimWave {
        if (!Double.isFinite(height) || height < 0D) height = 0D;
        height = Math.min(MAX_HEIGHT, height);
        periodTicks = Math.max(MIN_PERIOD_TICKS, periodTicks);
    }

    /** Whether it swims any wave at all. */
    public boolean waves() {
        return height > 0D;
    }
}
