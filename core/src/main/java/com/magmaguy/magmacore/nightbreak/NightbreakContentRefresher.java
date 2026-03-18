package com.magmaguy.magmacore.nightbreak;

import com.magmaguy.magmacore.util.Logger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;

public class NightbreakContentRefresher {
    private NightbreakContentRefresher() {
    }

    public static <T extends NightbreakManagedContent> void refreshAsync(JavaPlugin plugin,
                                                                         Collection<T> packages,
                                                                         Predicate<T> shouldCheckVersion,
                                                                         Consumer<List<T>> onComplete) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            Map<String, NightbreakAccount.VersionInfo> versionInfoBySlug = new LinkedHashMap<>();
            NightbreakAccount account = NightbreakAccount.getInstance();
            if (account != null) {
                versionInfoBySlug.putAll(account.getAllVersions());
            }
            if (!versionInfoBySlug.isEmpty()) {
                NightbreakContentManager.getVersionCache().clear();
                NightbreakContentManager.getVersionCache().putAll(versionInfoBySlug);
            }

            Map<String, NightbreakAccount.AccessInfo> accessInfoBySlug = new LinkedHashMap<>();
            List<T> newlyOutdated = new ArrayList<>();

            for (T contentPackage : packages) {
                String slug = contentPackage.getNightbreakSlug();
                if (slug == null || slug.isEmpty()) continue;

                if (NightbreakAccount.hasToken()) {
                    NightbreakAccount.AccessInfo accessInfo = accessInfoBySlug.computeIfAbsent(slug, key -> {
                        NightbreakAccount.AccessInfo fetched = NightbreakAccount.getInstance().checkAccess(key);
                        if (fetched != null) {
                            NightbreakContentManager.getAccessCache().put(key, fetched);
                        }
                        return fetched;
                    });
                    contentPackage.setCachedAccessInfo(accessInfo);
                } else {
                    contentPackage.setCachedAccessInfo(null);
                }

                if (!shouldCheckVersion.test(contentPackage) || !contentPackage.isInstalled()) continue;

                NightbreakAccount.VersionInfo versionInfo = versionInfoBySlug.get(slug);
                if (versionInfo == null) continue;

                boolean outOfDate = versionInfo.versionInt > contentPackage.getLocalVersion();
                if (outOfDate && !contentPackage.isOutOfDate()) {
                    newlyOutdated.add(contentPackage);
                    Logger.warn("Content " + contentPackage.getDisplayName() +
                            " is outdated! Your version: " + contentPackage.getLocalVersion() +
                            " / remote version: " + versionInfo.versionInt +
                            " / link: " + contentPackage.getDownloadLink());
                }
                contentPackage.setOutOfDate(outOfDate);
            }

            Bukkit.getScheduler().runTask(plugin, () -> onComplete.accept(newlyOutdated));
        });
    }
}
