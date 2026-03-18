package com.magmaguy.magmacore.nightbreak;

import java.util.List;

public record NightbreakFirstTimeSetupSpec(String pluginDisplayName,
                                           String adminPermission,
                                           String initializeCommand,
                                           String setupCommand,
                                           String downloadAllCommand,
                                           String contentUrl,
                                           String supportUrl,
                                           List<String> warningNotes,
                                           List<String> recommendedNotes) {
    public NightbreakFirstTimeSetupSpec {
        initializeCommand = initializeCommand == null ? "" : initializeCommand;
        downloadAllCommand = downloadAllCommand == null ? "" : downloadAllCommand;
        warningNotes = warningNotes == null ? List.of() : List.copyOf(warningNotes);
        recommendedNotes = recommendedNotes == null ? List.of() : List.copyOf(recommendedNotes);
    }
}
