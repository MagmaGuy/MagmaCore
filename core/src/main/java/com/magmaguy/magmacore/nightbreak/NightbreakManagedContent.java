package com.magmaguy.magmacore.nightbreak;

import java.util.concurrent.CompletableFuture;

public interface NightbreakManagedContent {
    String getNightbreakSlug();

    String getDisplayName();

    String getDownloadLink();

    int getLocalVersion();

    boolean isInstalled();

    boolean isDownloaded();

    boolean isOutOfDate();

    void setOutOfDate(boolean outOfDate);

    NightbreakAccount.AccessInfo getCachedAccessInfo();

    void setCachedAccessInfo(NightbreakAccount.AccessInfo accessInfo);

    /**
     * True when the plugin itself holds this package back until a later release, whatever the Nightbreak catalog
     * says. Such a package is never downloaded, and the setup menu lists it under Coming soon until it is.
     */
    default boolean isMarkedComingSoon() {
        return false;
    }

    default CompletableFuture<Void> enableAfterDownload() {
        return CompletableFuture.completedFuture(null);
    }
}
