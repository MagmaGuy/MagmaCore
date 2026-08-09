package com.magmaguy.magmacore.nightbreak;

/**
 * Player-facing text used by the shared {@code /nightbreaklogout} command.
 * Consumer plugins may provide configuration-backed or translated values
 * through {@link NightbreakPluginSpec}.
 */
public record NightbreakLogoutMessages(
        String commandDescription,
        String permissionDenied,
        String success,
        String reconnect,
        String failure) {

    public NightbreakLogoutMessages {
        commandDescription = valueOrDefault(commandDescription,
                "Remove the registered Nightbreak account token");
        permissionDenied = valueOrDefault(permissionDenied,
                "&cYou don't have permission to use this command.");
        success = valueOrDefault(success,
                "&aThis server is no longer connected to a Nightbreak account.");
        reconnect = valueOrDefault(reconnect,
                "&7Use &a/nightbreaklogin <token> &7to connect it again.");
        failure = valueOrDefault(failure,
                "&cThe shared Nightbreak account token could not be removed.");
    }

    public static NightbreakLogoutMessages defaults() {
        return new NightbreakLogoutMessages(null, null, null, null, null);
    }

    private static String valueOrDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
