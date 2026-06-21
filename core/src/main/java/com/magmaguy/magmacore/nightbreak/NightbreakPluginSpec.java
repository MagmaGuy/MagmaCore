package com.magmaguy.magmacore.nightbreak;

import java.net.URI;

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

    public String pluginSlug() {
        if (contentUrl == null || contentUrl.isBlank()) return rootCommand;
        try {
            URI uri = URI.create(contentUrl);
            String[] parts = uri.getPath().split("/");
            for (int i = 0; i < parts.length - 1; i++) {
                if ("plugin".equals(parts[i]) && !parts[i + 1].isBlank()) {
                    return parts[i + 1];
                }
            }
        } catch (IllegalArgumentException ignored) {
            // Fall through to the root command. Older plugins sometimes pass
            // hand-written URLs; root command is still a stable local id.
        }
        return rootCommand;
    }

    public String downloadPageUrl() {
        String slug = pluginSlug();
        return "https://nightbreak.io/plugin/" + slug + "/download/";
    }

    public String contentUrlWithTrailingSlash() {
        if (contentUrl == null || contentUrl.isBlank()) {
            return "https://nightbreak.io/plugin/" + pluginSlug() + "/";
        }
        return contentUrl.endsWith("/") ? contentUrl : contentUrl + "/";
    }
}
