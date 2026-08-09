package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.function.Supplier;

public class NightbreakContentRefresher {
    private static final ConcurrentMap<RefreshKey, Long> LAST_REFRESH_NANOS =
            new ConcurrentHashMap<>();

    private record RefreshKey(String pluginName, String catalogKey) {
    }

    private NightbreakContentRefresher() {
    }

    /**
     * Starts a refresh only when the keyed cooldown has elapsed. The gate is
     * stamped atomically, before the package supplier is evaluated or any work
     * is scheduled, so concurrent menu opens cannot duplicate the request.
     */
    public static <T extends NightbreakManagedContent> boolean refreshAsyncIfDue(
            JavaPlugin plugin,
            String catalogKey,
            Duration cooldown,
            Supplier<? extends Collection<? extends T>> packageSupplier,
            Predicate<T> shouldCheckVersion,
            Consumer<List<T>> onComplete) {
        Objects.requireNonNull(plugin, "plugin");
        Objects.requireNonNull(catalogKey, "catalogKey");
        Objects.requireNonNull(cooldown, "cooldown");
        Objects.requireNonNull(packageSupplier, "packageSupplier");
        if (cooldown.isNegative()) throw new IllegalArgumentException("cooldown must not be negative");

        RefreshKey key = refreshKey(plugin, catalogKey);
        long now = System.nanoTime();
        long cooldownNanos = cooldown.toNanos();
        AtomicBoolean accepted = new AtomicBoolean(false);
        LAST_REFRESH_NANOS.compute(key, (ignored, previous) -> {
            if (previous != null && now - previous < cooldownNanos) return previous;
            accepted.set(true);
            return now;
        });
        if (!accepted.get()) return false;

        Collection<? extends T> suppliedPackages =
                Objects.requireNonNull(packageSupplier.get(), "packageSupplier result");
        refreshAsync(plugin, new ArrayList<>(suppliedPackages), shouldCheckVersion, onComplete);
        return true;
    }

    /**
     * Clears only the calling plugin/catalog gate; unrelated consumers and
     * in-flight refreshes are intentionally untouched.
     */
    public static void resetRefreshCooldown(JavaPlugin plugin, String catalogKey) {
        LAST_REFRESH_NANOS.remove(refreshKey(
                Objects.requireNonNull(plugin, "plugin"),
                Objects.requireNonNull(catalogKey, "catalogKey")));
    }

    /**
     * Clears every refresh gate owned by a plugin lifecycle. In-flight work is
     * fenced separately by the lifecycle operation gate.
     */
    public static void shutdown(JavaPlugin plugin) {
        String pluginName = Objects.requireNonNull(plugin, "plugin")
                .getName().toLowerCase(Locale.ROOT);
        LAST_REFRESH_NANOS.keySet().removeIf(key -> key.pluginName().equals(pluginName));
    }

    private static RefreshKey refreshKey(JavaPlugin plugin, String catalogKey) {
        return new RefreshKey(
                plugin.getName().toLowerCase(Locale.ROOT),
                catalogKey.toLowerCase(Locale.ROOT));
    }

    public static <T extends NightbreakManagedContent> void refreshAsync(JavaPlugin plugin,
                                                                         Collection<T> packages,
                                                                         Predicate<T> shouldCheckVersion,
                                                                         Consumer<List<T>> onComplete) {
        NightbreakContentManager.OperationGate lifecycleGate =
                NightbreakBulkDownloader.captureOperationGate(plugin);
        refreshAsync(plugin, packages, shouldCheckVersion,
                lifecycleGate::isCurrent, onComplete, () -> {});
    }

    public static <T extends NightbreakManagedContent> void refreshAsync(JavaPlugin plugin,
                                                                         Collection<T> packages,
                                                                         Predicate<T> shouldCheckVersion,
                                                                         BooleanSupplier operationCurrent,
                                                                         Consumer<List<T>> onComplete,
                                                                         Runnable onCancelled) {
        AtomicBoolean cancellationNotified = new AtomicBoolean(false);
        Runnable cancelOnce = () -> {
            if (cancellationNotified.compareAndSet(false, true)) onCancelled.run();
        };
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            if (!operationCurrent.getAsBoolean()) {
                cancelOnce.run();
                return;
            }
            Map<String, NightbreakAccount.VersionInfo> versionInfoBySlug = new LinkedHashMap<>();
            NightbreakAccount account = NightbreakAccount.getInstance();
            if (account != null) {
                versionInfoBySlug.putAll(account.getAllVersions());
            }
            if (!operationCurrent.getAsBoolean()) {
                cancelOnce.run();
                return;
            }
            if (!versionInfoBySlug.isEmpty()) {
                NightbreakContentManager.getVersionCache().clear();
                NightbreakContentManager.getVersionCache().putAll(versionInfoBySlug);
            }

            Map<String, NightbreakAccount.AccessInfo> accessInfoBySlug = new LinkedHashMap<>();
            List<T> newlyOutdated = new ArrayList<>();

            for (T contentPackage : packages) {
                if (!operationCurrent.getAsBoolean()) {
                    cancelOnce.run();
                    return;
                }
                String slug = contentPackage.getNightbreakSlug();
                if (slug == null || slug.isEmpty()) continue;

                NightbreakAccount iterationAccount = NightbreakAccount.getInstance();
                if (iterationAccount != null) {
                    if (NightbreakAccount.hasAuthFailure()) {
                        contentPackage.setCachedAccessInfo(null);
                        break;
                    }
                    NightbreakAccount.AccessInfo accessInfo = accessInfoBySlug.computeIfAbsent(slug, key -> {
                        if (!operationCurrent.getAsBoolean()) return null;
                        NightbreakAccount.AccessInfo fetched = iterationAccount.checkAccess(key);
                        if (fetched != null && operationCurrent.getAsBoolean()) {
                            NightbreakContentManager.getAccessCache().put(key, fetched);
                        }
                        return fetched;
                    });
                    if (!operationCurrent.getAsBoolean()) {
                        cancelOnce.run();
                        return;
                    }
                    contentPackage.setCachedAccessInfo(accessInfo);
                    if (NightbreakAccount.hasAuthFailure()) {
                        break;
                    }
                } else {
                    contentPackage.setCachedAccessInfo(null);
                }

                if (!shouldCheckVersion.test(contentPackage) || !contentPackage.isInstalled()) continue;

                NightbreakAccount.VersionInfo versionInfo = versionInfoBySlug.get(slug);
                if (versionInfo == null) continue;

                boolean outOfDate = versionInfo.versionInt > contentPackage.getLocalVersion();
                if (!operationCurrent.getAsBoolean()) {
                    cancelOnce.run();
                    return;
                }
                if (outOfDate && !contentPackage.isOutOfDate()) {
                    newlyOutdated.add(contentPackage);
                    Logger.warn("Content " + contentPackage.getDisplayName() +
                            " is outdated! Your version: " + contentPackage.getLocalVersion() +
                            " / remote version: " + versionInfo.versionInt +
                            " / link: " + contentPackage.getDownloadLink());
                }
                contentPackage.setOutOfDate(outOfDate);
            }

            if (!operationCurrent.getAsBoolean()) {
                cancelOnce.run();
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> {
                    if (operationCurrent.getAsBoolean()) onComplete.accept(newlyOutdated);
                    else cancelOnce.run();
                });
            } catch (RuntimeException exception) {
                cancelOnce.run();
            }
        });
    }
}
