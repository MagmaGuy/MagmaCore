package com.magmaguy.magmacore.nightbreak;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitTask;

import java.util.UUID;

/**
 * Connects this server to a Nightbreak account without copying a token.
 * <p>
 * The server asks nightbreak.io for a code, prints a link carrying it, and polls
 * with a secret only it holds. When the account owner approves the code in a
 * browser, the next poll returns the account token, which is saved exactly as
 * {@code /nightbreaklogin <token>} saves it. The protocol is described in the
 * account manager's docs/plans/2026-10-26-server-link-design.md.
 * <p>
 * One link is active per shaded copy; starting another replaces it.
 */
public final class NightbreakServerLink {
    private static final Object LOCK = new Object();
    private static final int DEFAULT_POLL_SECONDS = 5;
    /** Polling stops by then whatever expiry nightbreak.io reports. */
    private static final long MAX_LINK_MILLIS = 35L * 60L * 1000L;
    private static final long CONFIRM_MILLIS = 5L * 60L * 1000L;
    private static Session active;
    private static PendingSwitch pendingSwitch;

    /**
     * An approved token held back because this server already had a working
     * one: anyone who saw the code could have approved the link, so switching
     * accounts waits for {@code /nightbreaklogin confirm}.
     */
    private record PendingSwitch(String token, String accountName, long expiresAtMillis) { }

    private NightbreakServerLink() {
    }

    /**
     * Starts a link and reports it to the requester, a player or the console.
     * The result of the link is reported to the same requester if they are
     * still online, and always to the console.
     */
    public static void start(JavaPlugin plugin, CommandSender requester) {
        UUID playerId = requester instanceof Player player ? player.getUniqueId() : null;
        JsonObject body = new JsonObject();
        body.addProperty("serverName", serverName());
        body.addProperty("pluginName", plugin.getName());
        body.addProperty("pluginVersion", plugin.getDescription().getVersion());
        body.addProperty("minecraftVersion", minecraftVersion());

        if (NightbreakAccount.hasToken()) {
            Logger.sendSimpleMessage(requester, savedTokenIsDead()
                    ? "&7The saved token was rejected, so an approved link replaces it."
                    : "&7This server is already connected. If the link is approved, you confirm the switch with &a/nightbreaklogin confirm&7.");
        }
        Logger.sendSimpleMessage(requester, "&7Asking nightbreak.io for a link...");
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            NightbreakAccount.JsonResponse response = NightbreakAccount.postPublicJson("/server/link/start", body);
            Bukkit.getScheduler().runTask(plugin, () -> onStarted(plugin, playerId, response));
        });
    }

    /**
     * Switches this server to a token a link delivered while another working
     * token was saved. See {@link PendingSwitch}.
     */
    public static void confirm(JavaPlugin plugin, CommandSender sender) {
        PendingSwitch pending;
        synchronized (LOCK) {
            pending = pendingSwitch;
            pendingSwitch = null;
        }
        if (pending == null || System.currentTimeMillis() > pending.expiresAtMillis()) {
            Logger.sendSimpleMessage(sender, "&eThere is no approved link waiting for confirmation. Run &a/nightbreaklogin&e to start one.");
            return;
        }
        applyToken(plugin, sender, pending.token(), pending.accountName());
    }

    /**
     * Whether nightbreak.io rejected the saved token as invalid or revoked
     * (401). A 403 means the token still works but is refused for this network
     * or for one package, so it is not a reason to replace it unasked.
     */
    private static boolean savedTokenIsDead() {
        return NightbreakAccount.hasAuthFailure() && NightbreakAccount.getLastAuthFailureStatus() == 401;
    }

    private static void applyToken(JavaPlugin plugin, CommandSender requester, String token, String accountName) {
        if (NightbreakAccount.registerToken(plugin, token) == null) {
            Logger.sendSimpleMessage(requester, "&cThe link was approved, but the token could not be saved. Check the console for errors.");
            return;
        }
        NightbreakCatalogMenu.sendLoginSuccess(requester, plugin, accountName);
        Logger.sendSimpleMessage(requester, "&7Not your account? Run &a/nightbreaklogout&7 to disconnect it.");
        if (requester instanceof Player) {
            Logger.info("This server is now linked to the Nightbreak account"
                    + (accountName == null ? "" : " " + accountName) + ".");
        }
    }

    private static void onStarted(JavaPlugin plugin, UUID playerId, NightbreakAccount.JsonResponse response) {
        CommandSender requester = requester(playerId);
        JsonObject body = response.body();
        String rawCode = string(body, "code");
        String code = rawCode == null || !rawCode.matches("[A-Z0-9-]{4,16}") ? null : rawCode;
        String pollSecret = string(body, "pollSecret");
        // The link must point at the same site this server talks to, for exactly this code.
        String linkUrl = code == null ? null : NightbreakAccount.baseUrl() + "/server/link/" + code;
        if (response.status() != 200 || code == null || pollSecret == null
                || !linkUrl.equals(string(body, "linkUrl"))) {
            sendStartFailure(requester, response.status());
            return;
        }

        long now = System.currentTimeMillis();
        long expiresAtMillis = Math.min(
                parseInstantMillis(string(body, "expiresAt"), now + MAX_LINK_MILLIS),
                now + MAX_LINK_MILLIS);
        int pollSeconds = Math.max(2, integer(body, "pollIntervalSeconds", DEFAULT_POLL_SECONDS));
        Session session = new Session(plugin, playerId, code, pollSecret, expiresAtMillis, pollSeconds);
        synchronized (LOCK) {
            if (active != null) active.cancel();
            active = session;
        }

        long minutes = Math.max(1, Math.round((expiresAtMillis - System.currentTimeMillis()) / 60000.0));
        sendLink(requester, linkUrl, code, minutes);
        if (requester instanceof Player player) {
            Logger.info(player.getName() + " started a Nightbreak server link with code " + code + ".");
        }
        session.schedulePoll();
    }

    private static void sendLink(CommandSender sender, String linkUrl, String code, long minutes) {
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
        Logger.sendSimpleMessage(sender, "<g:#8B0000:#CC4400:#DAA520>Link this server to your Nightbreak account</g>");
        if (sender instanceof Player player) {
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage("&7Open: "),
                    SpigotMessage.hoverLinkMessage("&9&n" + linkUrl,
                            "&7Click to sign in and approve this server.",
                            linkUrl));
        } else {
            Logger.sendSimpleMessage(sender, "&7Open: &9" + linkUrl);
        }
        Logger.sendSimpleMessage(sender, "&7Code: &e&l" + code + "&7 (the page shows the same code)");
        Logger.sendSimpleMessage(sender, "&7Sign in or create a free account, then approve the server. The link expires in "
                + minutes + " minutes.");
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
    }

    private static void sendStartFailure(CommandSender sender, int status) {
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
        Logger.sendSimpleMessage(sender, switch (status) {
            case 0 -> "&cCould not reach nightbreak.io. Check the server's internet connection and try again.";
            case 429 -> "&cToo many link requests from this server. Wait a few minutes and try again.";
            case 503 -> "&eServer linking is not available right now.";
            default -> "&cnightbreak.io could not start a link (HTTP " + status + ").";
        });
        sendTokenFallback(sender);
        Logger.sendSimpleMessage(sender, NightbreakSetupMenuHelper.getSeparator());
    }

    private static void sendTokenFallback(CommandSender sender) {
        if (sender instanceof Player player) {
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage("&7You can also copy your token from "),
                    SpigotMessage.hoverLinkMessage("&9&nhttps://nightbreak.io/account/",
                            "&7Click to open the account token page.",
                            "https://nightbreak.io/account/"),
                    SpigotMessage.simpleMessage(" &7and run &a/nightbreaklogin <token>&7."));
        } else {
            Logger.sendSimpleMessage(sender, "&7You can also copy your token from &9https://nightbreak.io/account/"
                    + "&7 and run &a/nightbreaklogin <token>&7.");
        }
    }

    private static CommandSender requester(UUID playerId) {
        Player player = playerId == null ? null : Bukkit.getPlayer(playerId);
        return player != null ? player : Bukkit.getConsoleSender();
    }

    /** Sends to the requester and, when that is a player, a plain summary to the console. */
    private static void report(UUID playerId, String requesterMessage, String consoleSummary) {
        CommandSender requester = requester(playerId);
        Logger.sendSimpleMessage(requester, requesterMessage);
        if (requester instanceof Player) Logger.info(consoleSummary);
    }

    private static String serverName() {
        String motd = Bukkit.getMotd();
        if (motd == null) return null;
        String plain = ChatColor.stripColor(motd).replaceAll("\\s+", " ").trim();
        return plain.isEmpty() ? null : plain;
    }

    private static String minecraftVersion() {
        String version = Bukkit.getBukkitVersion();
        int suffix = version.indexOf('-');
        return Bukkit.getName() + " " + (suffix > 0 ? version.substring(0, suffix) : version);
    }

    private static String string(JsonObject body, String key) {
        if (body == null) return null;
        JsonElement element = body.get(key);
        return element != null && element.isJsonPrimitive() ? element.getAsString() : null;
    }

    private static int integer(JsonObject body, String key, int fallback) {
        if (body == null) return fallback;
        JsonElement element = body.get(key);
        try {
            return element != null && element.isJsonPrimitive() ? element.getAsInt() : fallback;
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private static long parseInstantMillis(String value, long fallback) {
        if (value == null) return fallback;
        try {
            return java.time.Instant.parse(value).toEpochMilli();
        } catch (java.time.format.DateTimeParseException exception) {
            return fallback;
        }
    }

    private static final class Session {
        private final JavaPlugin plugin;
        private final UUID playerId;
        private final String code;
        private final String pollSecret;
        private final long expiresAtMillis;
        private final int pollSeconds;
        private BukkitTask nextPoll;
        private boolean cancelled;

        private Session(JavaPlugin plugin, UUID playerId, String code, String pollSecret,
                        long expiresAtMillis, int pollSeconds) {
            this.plugin = plugin;
            this.playerId = playerId;
            this.code = code;
            this.pollSecret = pollSecret;
            this.expiresAtMillis = expiresAtMillis;
            this.pollSeconds = pollSeconds;
        }

        /** Polls are chained, never overlapping: the next is scheduled after the previous answer. */
        private void schedulePoll() {
            synchronized (LOCK) {
                if (cancelled || !plugin.isEnabled()) return;
                nextPoll = Bukkit.getScheduler().runTaskLaterAsynchronously(plugin, this::poll, pollSeconds * 20L);
            }
        }

        private void cancel() {
            synchronized (LOCK) {
                cancelled = true;
                if (nextPoll != null) nextPoll.cancel();
                if (active == this) active = null;
            }
        }

        private boolean isCurrent() {
            synchronized (LOCK) {
                return !cancelled && active == this;
            }
        }

        private void poll() {
            if (!isCurrent()) return;
            // Give the server a few seconds past expiry to hear an approval made at the last moment.
            if (System.currentTimeMillis() > expiresAtMillis + 10_000L) {
                finish(this::reportExpired);
                return;
            }

            JsonObject body = new JsonObject();
            body.addProperty("pollSecret", pollSecret);
            NightbreakAccount.JsonResponse response = NightbreakAccount.postPublicJson("/server/link/poll", body);
            String status = string(response.body(), "status");

            if (response.status() == 200 && "approved".equals(status)) {
                String token = string(response.body(), "token");
                // A display name is free text; drop what chat formatting would interpret.
                String rawName = string(response.body(), "accountName");
                String accountName = rawName == null ? null : rawName.replaceAll("[<>&§]", "");
                if (token != null) {
                    finish(() -> saveToken(token, accountName));
                    return;
                }
            } else if (response.status() == 200 && "denied".equals(status)) {
                finish(() -> report(playerId,
                        "&eThe Nightbreak link " + code + " was declined. Run &a/nightbreaklogin&e to start a new one.",
                        "The Nightbreak link " + code + " was declined."));
                return;
            } else if ((response.status() == 200 && "expired".equals(status)) || response.status() == 404) {
                finish(this::reportExpired);
                return;
            } else if (response.status() == 503) {
                finish(() -> {
                    CommandSender requester = requester(playerId);
                    Logger.sendSimpleMessage(requester, "&eServer linking stopped being available before the link was approved.");
                    sendTokenFallback(requester);
                });
                return;
            }
            // Pending, rate limited, or a network blip: ask again.
            schedulePoll();
        }

        private void reportExpired() {
            report(playerId,
                    "&eThe Nightbreak link " + code + " expired before it was approved. Run &a/nightbreaklogin&e to get a new one.",
                    "The Nightbreak link " + code + " expired before it was approved.");
        }

        private void saveToken(String token, String accountName) {
            CommandSender requester = requester(playerId);
            if (NightbreakAccount.hasToken() && !savedTokenIsDead()) {
                synchronized (LOCK) {
                    pendingSwitch = new PendingSwitch(token, accountName, System.currentTimeMillis() + CONFIRM_MILLIS);
                }
                String account = accountName == null ? "a Nightbreak account" : "the Nightbreak account &f" + accountName + "&e";
                report(playerId,
                        "&eThe link was approved by " + account + ". This server is already connected with a working token,"
                                + " so it keeps that one until you run &a/nightbreaklogin confirm&e (within 5 minutes).",
                        "A Nightbreak link was approved by " + (accountName == null ? "an account" : accountName)
                                + ". Run /nightbreaklogin confirm within 5 minutes to switch this server to it.");
                return;
            }
            applyToken(plugin, requester, token, accountName);
        }

        /** Ends this session and runs the outcome on the main thread. */
        private void finish(Runnable outcome) {
            synchronized (LOCK) {
                if (cancelled || active != this) return;
                cancelled = true;
                active = null;
            }
            if (plugin.isEnabled()) Bukkit.getScheduler().runTask(plugin, outcome);
        }
    }
}
