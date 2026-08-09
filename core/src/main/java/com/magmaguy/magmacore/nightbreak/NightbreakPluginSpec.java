package com.magmaguy.magmacore.nightbreak;

import java.net.URI;
import java.util.function.Supplier;

public record NightbreakPluginSpec(String displayName,
                                   String rootCommand,
                                   String adminPermission,
                                   String setupPermission,
                                   String initializePermission,
                                   String contentUrl,
                                   String reloadSuccessMessage,
                                   boolean hasContentPackages,
                                   boolean hasPresetModes,
                                   boolean hasImportSystem,
                                   Supplier<NightbreakChangelogMessages> changelogMessagesSupplier,
                                   Supplier<NightbreakLogoutMessages> logoutMessagesSupplier) {

    /**
     * Backwards-compatible constructor for callers that configure the full
     * capability set and changelog copy but use the shared logout copy.
     */
    public NightbreakPluginSpec(String displayName,
                                String rootCommand,
                                String adminPermission,
                                String setupPermission,
                                String initializePermission,
                                String contentUrl,
                                String reloadSuccessMessage,
                                boolean hasContentPackages,
                                boolean hasPresetModes,
                                boolean hasImportSystem,
                                Supplier<NightbreakChangelogMessages> changelogMessagesSupplier) {
        this(displayName, rootCommand, adminPermission, setupPermission,
                initializePermission, contentUrl, reloadSuccessMessage,
                hasContentPackages, hasPresetModes, hasImportSystem,
                changelogMessagesSupplier, NightbreakLogoutMessages::defaults);
    }

    /**
     * Backwards-compatible constructor for callers that configure the full
     * plugin capability set but use the shared English changelog copy.
     */
    public NightbreakPluginSpec(String displayName,
                                String rootCommand,
                                String adminPermission,
                                String setupPermission,
                                String initializePermission,
                                String contentUrl,
                                String reloadSuccessMessage,
                                boolean hasContentPackages,
                                boolean hasPresetModes,
                                boolean hasImportSystem) {
        this(displayName, rootCommand, adminPermission, setupPermission,
                initializePermission, contentUrl, reloadSuccessMessage,
                hasContentPackages, hasPresetModes, hasImportSystem,
                NightbreakChangelogMessages::defaults,
                NightbreakLogoutMessages::defaults);
    }

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
                true, false, true, NightbreakChangelogMessages::defaults,
                NightbreakLogoutMessages::defaults);
    }

    /**
     * Constructor for plugins that retain the default content capabilities but
     * provide configuration-backed or translated changelog copy.
     */
    public NightbreakPluginSpec(String displayName,
                                String rootCommand,
                                String adminPermission,
                                String setupPermission,
                                String initializePermission,
                                String contentUrl,
                                String reloadSuccessMessage,
                                Supplier<NightbreakChangelogMessages> changelogMessagesSupplier) {
        this(displayName, rootCommand, adminPermission, setupPermission,
                initializePermission, contentUrl, reloadSuccessMessage,
                true, false, true, changelogMessagesSupplier,
                NightbreakLogoutMessages::defaults);
    }

    /**
     * Constructor for plugins with default content capabilities and
     * configuration-backed changelog and logout copy.
     */
    public NightbreakPluginSpec(String displayName,
                                String rootCommand,
                                String adminPermission,
                                String setupPermission,
                                String initializePermission,
                                String contentUrl,
                                String reloadSuccessMessage,
                                Supplier<NightbreakChangelogMessages> changelogMessagesSupplier,
                                Supplier<NightbreakLogoutMessages> logoutMessagesSupplier) {
        this(displayName, rootCommand, adminPermission, setupPermission,
                initializePermission, contentUrl, reloadSuccessMessage,
                true, false, true, changelogMessagesSupplier,
                logoutMessagesSupplier);
    }

    public NightbreakChangelogMessages resolveChangelogMessages() {
        if (changelogMessagesSupplier == null) return NightbreakChangelogMessages.defaults();
        try {
            NightbreakChangelogMessages messages = changelogMessagesSupplier.get();
            return messages == null ? NightbreakChangelogMessages.defaults() : messages;
        } catch (RuntimeException ignored) {
            return NightbreakChangelogMessages.defaults();
        }
    }

    public NightbreakLogoutMessages resolveLogoutMessages() {
        if (logoutMessagesSupplier == null) return NightbreakLogoutMessages.defaults();
        try {
            NightbreakLogoutMessages messages = logoutMessagesSupplier.get();
            return messages == null ? NightbreakLogoutMessages.defaults() : messages;
        } catch (RuntimeException ignored) {
            return NightbreakLogoutMessages.defaults();
        }
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
