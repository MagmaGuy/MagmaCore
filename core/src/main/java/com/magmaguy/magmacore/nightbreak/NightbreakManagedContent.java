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

    default CompletableFuture<Void> enableAfterDownload() {
        return CompletableFuture.completedFuture(null);
    }
}
