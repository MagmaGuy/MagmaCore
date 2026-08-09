package com.magmaguy.magmacore.nightbreak;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class NightbreakBulkDownloaderTest {
    @Test
    void fullInstallLetsAuthoritativeDownloadCheckOverrideDeniedCache() {
        TestContent content = new TestContent(
                "package",
                false,
                false,
                deniedAccess());

        List<TestContent> selected =
                NightbreakBulkDownloader.collectPackages(
                        List.of(content),
                        false);

        assertEquals(List.of(content), selected);
    }

    @Test
    void updateOnlyStillHonorsDeniedCache() {
        TestContent content = new TestContent(
                "package",
                true,
                true,
                deniedAccess());

        List<TestContent> selected =
                NightbreakBulkDownloader.collectPackages(
                        List.of(content),
                        true);

        assertTrue(selected.isEmpty());
    }

    @Test
    void fullInstallDeduplicatesSharedSlugs() {
        TestContent first = new TestContent(
                "shared",
                false,
                false,
                null);
        TestContent second = new TestContent(
                "shared",
                false,
                false,
                null);

        List<TestContent> selected =
                NightbreakBulkDownloader.collectPackages(
                        List.of(first, second),
                        false);

        assertEquals(List.of(first), selected);
    }

    private static NightbreakAccount.AccessInfo deniedAccess() {
        NightbreakAccount.AccessInfo info =
                new NightbreakAccount.AccessInfo();
        info.hasAccess = false;
        return info;
    }

    private static final class TestContent
            implements NightbreakManagedContent {
        private final String slug;
        private final boolean downloaded;
        private boolean outOfDate;
        private NightbreakAccount.AccessInfo accessInfo;

        private TestContent(
                String slug,
                boolean downloaded,
                boolean outOfDate,
                NightbreakAccount.AccessInfo accessInfo) {
            this.slug = slug;
            this.downloaded = downloaded;
            this.outOfDate = outOfDate;
            this.accessInfo = accessInfo;
        }

        @Override
        public String getNightbreakSlug() {
            return slug;
        }

        @Override
        public String getDisplayName() {
            return slug;
        }

        @Override
        public String getDownloadLink() {
            return "";
        }

        @Override
        public int getLocalVersion() {
            return 0;
        }

        @Override
        public boolean isInstalled() {
            return downloaded;
        }

        @Override
        public boolean isDownloaded() {
            return downloaded;
        }

        @Override
        public boolean isOutOfDate() {
            return outOfDate;
        }

        @Override
        public void setOutOfDate(boolean outOfDate) {
            this.outOfDate = outOfDate;
        }

        @Override
        public NightbreakAccount.AccessInfo getCachedAccessInfo() {
            return accessInfo;
        }

        @Override
        public void setCachedAccessInfo(
                NightbreakAccount.AccessInfo accessInfo) {
            this.accessInfo = accessInfo;
        }
    }
}
