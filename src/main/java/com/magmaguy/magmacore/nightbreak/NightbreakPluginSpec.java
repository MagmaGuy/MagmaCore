package com.magmaguy.magmacore.nightbreak;

public record NightbreakPluginSpec(String displayName,
                                   String rootCommand,
                                   String adminPermission,
                                   String setupPermission,
                                   String initializePermission,
                                   String contentUrl,
                                   String reloadSuccessMessage,
                                   boolean hasContentPackages,
                                   boolean hasPresetModes,
                                   boolean hasImportSystem) {

    /**
     * Backwards-compatible constructor for existing plugins.
     * Defaults: hasContentPackages=true, hasPresetModes=false, hasImportSystem=true.
     */
    public NightbreakPluginSpec(String displayName,
                                String rootCommand,
                                String adminPermission,
                                String setupPermission,
                                String initializePermission,
                                String contentUrl,
                                String reloadSuccessMessage) {
        this(displayName, rootCommand, adminPermission, setupPermission,
                initializePermission, contentUrl, reloadSuccessMessage,
                true, false, true);
    }
}
