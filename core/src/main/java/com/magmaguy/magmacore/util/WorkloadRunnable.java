package com.magmaguy.magmacore.util;

import com.magmaguy.magmacore.MagmaCore;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayDeque;
import java.util.Deque;

public class WorkloadRunnable extends BukkitRunnable {
    private final Deque<Workload> workloadDeque = new ArrayDeque<>();
    private final long maxMillisPerTick;
    private final Runnable onComplete;

    public WorkloadRunnable(double percentageOfTick, Runnable onComplete) {
        this.maxMillisPerTick = Math.max((long) ((1.0 / 20.0) * percentageOfTick * 1000), 2L);
        this.onComplete = onComplete;
    }

    public void addWorkload(Workload workload) {
        workloadDeque.add(workload);
    }

    public void startSync() {
        this.runTaskTimer(MagmaCore.getInstance().getRequestingPlugin(), 0, 1);
    }

    public void startAsync() {
        this.runTaskTimerAsynchronously(MagmaCore.getInstance().getRequestingPlugin(), 0, 1);
    }

    @Override
    public void run() {
        long stopTime = System.currentTimeMillis() + maxMillisPerTick;
        while (!workloadDeque.isEmpty() && System.currentTimeMillis() < stopTime) {
            Workload workload = workloadDeque.poll();
            if (workload != null) {
                workload.compute();
            }
        }

        if (workloadDeque.isEmpty()) {
            if (onComplete != null) {
                onComplete.run();
            }
            this.cancel();
        }
    }
}