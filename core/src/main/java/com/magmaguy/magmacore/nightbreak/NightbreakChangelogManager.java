package com.magmaguy.magmacore.nightbreak;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.magmaguy.magmacore.dialog.DialogManager;
import com.magmaguy.magmacore.util.SpigotMessage;
import com.magmaguy.magmacore.util.VersionChecker;
import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.SimpleCommandMap;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.HandlerList;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.io.File;
import java.io.IOException;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/**
 * Plugin-scoped, remote-backed changelog inbox. The only local data is the
 * version tracking floor and each administrator's read/opt-out state.
 */
public final class NightbreakChangelogManager implements Listener {
    private static final long RETRY_TICKS = 20L * 60L * 15L;
    private static final int MAX_DIALOG_RELEASES = 20;
    private static final int MAX_RELEASE_TEXT_LENGTH = 6_000;
    private static NightbreakChangelogManager active;
    private static JavaPlugin pendingContentPlugin;
    private static Supplier<? extends List<? extends NightbreakManagedContent>> pendingContentSupplier;

    private final JavaPlugin plugin;
    private final NightbreakPluginSpec spec;
    private final NightbreakChangelogMessages messages;
    private final File stateFile;
    private final YamlConfiguration state;
    private final String currentVersion;
    private final String trackingFloorVersion;
    private final boolean bootstrapCurrentRelease;
    private String commandLabel;
    private final AtomicBoolean fetching = new AtomicBoolean();
    private volatile List<NightbreakAccount.PluginChangelog> trackedPluginReleases = List.of();
    private volatile boolean pluginHistoryComplete;
    private volatile Map<String, List<NightbreakAccount.PluginChangelog>> trackedContentReleases = Map.of();
    private volatile Map<String, Boolean> trackedContentHistoryComplete = Map.of();
    private volatile List<TrackedContent> trackedContent = List.of();
    private final Map<UUID, String> lastNotifiedDigest = new java.util.HashMap<>();
    private int retryTaskId = -1;
    private int retryAttempts;
    private volatile boolean closed;
    private SimpleCommandMap registeredCommandMap;
    private ChangelogCommand registeredCommand;

    private NightbreakChangelogManager(JavaPlugin plugin, NightbreakPluginSpec spec) {
        this.plugin = plugin;
        this.spec = spec;
        this.messages = spec.resolveChangelogMessages();
        this.currentVersion = normalizeVersion(plugin.getDescription().getVersion());
        this.commandLabel = sanitizeCommandLabel(spec.rootCommand()) + "changelog";
        this.stateFile = new File(plugin.getDataFolder(), "nightbreak-changelogs.yml");
        this.state = YamlConfiguration.loadConfiguration(stateFile);

        String configuredFloor = normalizeVersion(state.getString("trackingSinceVersion"));
        boolean missingTrackingFloor = configuredFloor == null;
        if (configuredFloor == null) {
            configuredFloor = currentVersion;
            state.set("trackingSinceVersion", currentVersion);
            state.set("bootstrapCurrentRelease", currentVersion);
        }
        this.bootstrapCurrentRelease = missingTrackingFloor
                || sameVersion(state.getString("bootstrapCurrentRelease"), currentVersion);
        this.trackingFloorVersion = configuredFloor;
        state.set("lastObservedVersion", currentVersion);
        this.trackedPluginReleases = readCachedReleases("cache.plugin", currentVersion);
        this.pluginHistoryComplete = sameVersion(state.getString("cache.plugin.targetVersion"), currentVersion)
                && state.getBoolean("cache.plugin.complete", false);
        saveState();
    }

    public static synchronized void initialize(JavaPlugin plugin, NightbreakPluginSpec spec) {
        shutdown(plugin);
        if (plugin == null || spec == null) return;
        active = new NightbreakChangelogManager(plugin, spec);
        Bukkit.getPluginManager().registerEvents(active, plugin);
        active.registerCommand();
        if (pendingContentPlugin == plugin && pendingContentSupplier != null)
            active.registerPendingContent();
        if (active.bootstrapCurrentRelease
                || !sameVersion(active.trackingFloorVersion, active.currentVersion)) active.fetchAsync();
    }

    public static synchronized void shutdown(JavaPlugin plugin) {
        if (active == null || (plugin != null && active.plugin != plugin)) return;
        active.closed = true;
        HandlerList.unregisterAll(active);
        if (active.retryTaskId != -1) Bukkit.getScheduler().cancelTask(active.retryTaskId);
        active.unregisterCommand();
        active.lastNotifiedDigest.clear();
        if (pendingContentPlugin == active.plugin) {
            pendingContentPlugin = null;
            pendingContentSupplier = null;
        }
        active = null;
    }

    public static <T extends NightbreakManagedContent> void registerContentSupplier(
            JavaPlugin plugin, Supplier<List<T>> supplier) {
        NightbreakChangelogManager manager = active;
        if (plugin == null || supplier == null) return;
        pendingContentPlugin = plugin;
        pendingContentSupplier = supplier;
        if (manager == null || manager.closed || manager.plugin != plugin) return;
        manager.registerPendingContent();
    }

    private void registerPendingContent() {
        try {
            @SuppressWarnings("unchecked")
            List<? extends NightbreakManagedContent> supplied = pendingContentSupplier.get();
            registerContent(supplied);
        } catch (RuntimeException exception) {
            plugin.getLogger().warning("Could not register DLC changelog tracking: " + exception.getMessage());
        }
    }

    private void registerContent(List<? extends NightbreakManagedContent> packages) {
        List<TrackedContent> content = new ArrayList<>();
        for (NightbreakManagedContent contentPackage : packages) {
            if (contentPackage == null || !contentPackage.isInstalled()) continue;
            String slug = contentPackage.getNightbreakSlug();
            if (slug == null || slug.isBlank()) continue;
            String version = Integer.toString(contentPackage.getLocalVersion());
            String path = "content." + stateKey(slug);
            String floor = normalizeVersion(state.getString(path + ".trackingSinceVersion"));
            boolean missingFloor = floor == null;
            if (floor == null) {
                floor = version;
                state.set(path + ".trackingSinceVersion", version);
                state.set(path + ".bootstrapCurrentRelease", version);
            }
            state.set(path + ".lastObservedVersion", version);
            boolean bootstrap = missingFloor
                    || sameVersion(state.getString(path + ".bootstrapCurrentRelease"), version);
            content.add(new TrackedContent(slug, contentPackage.getDisplayName(), version, floor, bootstrap));
        }
        trackedContent = List.copyOf(content);
        Map<String, List<NightbreakAccount.PluginChangelog>> cachedContent = new LinkedHashMap<>();
        Map<String, Boolean> cachedCompleteness = new LinkedHashMap<>();
        for (TrackedContent tracked : trackedContent) {
            String cachePath = "cache.content." + stateKey(tracked.slug);
            List<NightbreakAccount.PluginChangelog> cached = readCachedReleases(
                    cachePath, tracked.currentVersion);
            if (!cached.isEmpty()) cachedContent.put(tracked.slug, cached);
            cachedCompleteness.put(tracked.slug,
                    sameVersion(state.getString(cachePath + ".targetVersion"), tracked.currentVersion)
                            && state.getBoolean(cachePath + ".complete", false));
        }
        trackedContentReleases = Map.copyOf(cachedContent);
        trackedContentHistoryComplete = Map.copyOf(cachedCompleteness);
        saveState();
        if (!trackedContent.isEmpty()) {
            fetchAsync();
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (!isAdmin(event.getPlayer()) || isPermanentlyDisabled(event.getPlayer())) return;
        lastNotifiedDigest.remove(event.getPlayer().getUniqueId());
        Bukkit.getScheduler().runTaskLater(plugin, () -> notifyIfPending(event.getPlayer()), 40L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        lastNotifiedDigest.remove(event.getPlayer().getUniqueId());
    }

    private void fetchAsync() {
        if (closed || !fetching.compareAndSet(false, true)) return;
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            List<NightbreakAccount.PluginChangelog> selected = trackedPluginReleases;
            boolean selectedPluginComplete = pluginHistoryComplete;
            boolean historyServiceUnavailable = false;
            boolean pluginNeedsHistory = bootstrapCurrentRelease
                    || NightbreakPluginUpdater.compareVersions(currentVersion, trackingFloorVersion) > 0;
            if (pluginNeedsHistory && !selectedPluginComplete) {
                List<NightbreakAccount.PluginChangelog> previouslySelected = selected;
                List<NightbreakAccount.PluginChangelog> releases =
                        NightbreakAccount.getPublicPluginChangelogs(spec.pluginSlug(), false);
                historyServiceUnavailable = releases.isEmpty();
                RangeSelection selection = bootstrapCurrentRelease
                        ? selectCurrentRelease(releases, currentVersion)
                        : selectRange(releases, trackingFloorVersion, currentVersion);
                if (selection.releases.size() >= previouslySelected.size()) selected = selection.releases;
                selectedPluginComplete = selection.complete;
                if (selected.isEmpty()) selected = latestReleaseFallback();
                if (bootstrapCurrentRelease && !selected.isEmpty()) selectedPluginComplete = true;
            }
            Map<String, List<NightbreakAccount.PluginChangelog>> selectedContent =
                    new LinkedHashMap<>(trackedContentReleases);
            Map<String, Boolean> selectedContentCompleteness =
                    new LinkedHashMap<>(trackedContentHistoryComplete);
            for (TrackedContent content : trackedContent) {
                boolean contentNeedsHistory = content.bootstrapCurrentRelease
                        || NightbreakPluginUpdater.compareVersions(content.currentVersion, content.floorVersion) > 0;
                if (!contentNeedsHistory) {
                    selectedContentCompleteness.put(content.slug, true);
                    continue;
                }
                if (selectedContentCompleteness.getOrDefault(content.slug, false)) continue;
                if (historyServiceUnavailable) continue;
                List<NightbreakAccount.PluginChangelog> contentHistory =
                        NightbreakAccount.getPublicDlcChangelogs(content.slug, false);
                if (contentHistory.isEmpty()) historyServiceUnavailable = true;
                RangeSelection selection = content.bootstrapCurrentRelease
                        ? selectCurrentRelease(contentHistory, content.currentVersion)
                        : selectRange(contentHistory, content.floorVersion, content.currentVersion);
                List<NightbreakAccount.PluginChangelog> contentRange = selection.releases;
                List<NightbreakAccount.PluginChangelog> previousRange = selectedContent.getOrDefault(content.slug, List.of());
                if (previousRange.size() > contentRange.size()) contentRange = previousRange;
                if (contentRange.isEmpty()) contentRange = latestContentReleaseFallback(content);
                if (!contentRange.isEmpty()) selectedContent.put(content.slug, contentRange);
                selectedContentCompleteness.put(content.slug,
                        selection.complete || (content.bootstrapCurrentRelease && !contentRange.isEmpty()));
            }
            List<NightbreakAccount.PluginChangelog> finalSelected = selected;
            boolean finalPluginComplete = selectedPluginComplete;
            if (closed || !plugin.isEnabled()) {
                fetching.set(false);
                return;
            }
            try {
                Bukkit.getScheduler().runTask(plugin, () -> applyFetchedResults(finalSelected,
                        finalPluginComplete, selectedContent, selectedContentCompleteness, pluginNeedsHistory));
            } catch (RuntimeException exception) {
                fetching.set(false);
                if (!closed) plugin.getLogger().warning("Could not apply fetched changelogs: " + exception.getMessage());
            }
        });
    }

    private void applyFetchedResults(List<NightbreakAccount.PluginChangelog> selectedPlugin,
                                     boolean selectedPluginComplete,
                                     Map<String, List<NightbreakAccount.PluginChangelog>> selectedContent,
                                     Map<String, Boolean> selectedContentCompleteness,
                                     boolean pluginNeedsHistory) {
        fetching.set(false);
        if (closed || !plugin.isEnabled()) return;
        trackedPluginReleases = selectedPlugin;
        pluginHistoryComplete = selectedPluginComplete;
        trackedContentReleases = Map.copyOf(selectedContent);
        trackedContentHistoryComplete = Map.copyOf(selectedContentCompleteness);
        if (!selectedPlugin.isEmpty())
            writeCachedReleases("cache.plugin", currentVersion, selectedPlugin, selectedPluginComplete);
        if (bootstrapCurrentRelease && selectedPluginComplete) state.set("bootstrapCurrentRelease", null);
        for (TrackedContent content : trackedContent) {
            List<NightbreakAccount.PluginChangelog> contentReleases = selectedContent.get(content.slug);
            if (contentReleases != null && !contentReleases.isEmpty())
                writeCachedReleases("cache.content." + stateKey(content.slug), content.currentVersion,
                        contentReleases, selectedContentCompleteness.getOrDefault(content.slug, false));
            if (content.bootstrapCurrentRelease
                    && selectedContentCompleteness.getOrDefault(content.slug, false))
                state.set("content." + stateKey(content.slug) + ".bootstrapCurrentRelease", null);
        }
        saveState();

        boolean expectedContentMissing = trackedContent.stream().anyMatch(content ->
                (content.bootstrapCurrentRelease
                        || NightbreakPluginUpdater.compareVersions(content.currentVersion, content.floorVersion) > 0)
                        && !selectedContentCompleteness.getOrDefault(content.slug, false));
        if ((pluginNeedsHistory && !selectedPluginComplete) || expectedContentMissing) scheduleRetry();
        else {
            if (retryTaskId != -1) Bukkit.getScheduler().cancelTask(retryTaskId);
            retryTaskId = -1;
            retryAttempts = 0;
        }
        Bukkit.getOnlinePlayers().forEach(this::notifyIfPending);
    }

    private List<NightbreakAccount.PluginChangelog> latestReleaseFallback() {
        NightbreakAccount.VersionInfo latest = NightbreakAccount.getPublicPluginVersion(spec.pluginSlug(), false);
        if (latest == null || !sameVersion(latest.version, currentVersion)
                || latest.changelog == null || latest.changelog.isBlank()) return List.of();
        return List.of(new NightbreakAccount.PluginChangelog(currentVersion, latest.changelog, null));
    }

    private List<NightbreakAccount.PluginChangelog> latestContentReleaseFallback(TrackedContent content) {
        NightbreakAccount.VersionInfo latest = NightbreakAccount.getPublicDlcVersion(content.slug, false);
        if (latest == null || !sameVersion(latest.version, content.currentVersion)
                || latest.changelog == null || latest.changelog.isBlank()) return List.of();
        return List.of(new NightbreakAccount.PluginChangelog(
                content.currentVersion, latest.changelog, null));
    }

    private RangeSelection selectCurrentRelease(List<NightbreakAccount.PluginChangelog> releases,
                                                String targetVersion) {
        int targetIndex = indexOfVersion(releases, targetVersion);
        if (targetIndex < 0) return new RangeSelection(List.of(), false);
        return new RangeSelection(List.of(releases.get(targetIndex)), true);
    }

    private RangeSelection selectRange(
            List<NightbreakAccount.PluginChangelog> releases, String floorVersion, String targetVersion) {
        if (NightbreakPluginUpdater.compareVersions(targetVersion, floorVersion) <= 0)
            return new RangeSelection(List.of(), true);
        int floorIndex = indexOfVersion(releases, floorVersion);
        int currentIndex = indexOfVersion(releases, targetVersion);
        if (currentIndex < 0) return new RangeSelection(List.of(), false);
        int start = floorIndex >= 0 ? floorIndex + 1 : Math.max(0, currentIndex - MAX_DIALOG_RELEASES + 1);
        if (start > currentIndex) return new RangeSelection(List.of(), true);
        return new RangeSelection(List.copyOf(releases.subList(start, currentIndex + 1)), true);
    }

    private void scheduleRetry() {
        if (closed || retryTaskId != -1) return;
        long delay = RETRY_TICKS * (1L << Math.min(retryAttempts++, 4));
        retryTaskId = Bukkit.getScheduler().scheduleSyncDelayedTask(plugin, () -> {
            retryTaskId = -1;
            fetchAsync();
        }, delay);
    }

    private void notifyIfPending(Player player) {
        if (closed || registeredCommand == null || player == null || !player.isOnline() || !isAdmin(player)
                || isPermanentlyDisabled(player) || !hasPending(player)) return;
        String digest = pendingDigest(player);
        if (digest.equals(lastNotifiedDigest.put(player.getUniqueId(), digest))) return;
        player.spigot().sendMessage(
                SpigotMessage.simpleMessage(format(messages.pendingNotice())),
                SpigotMessage.commandHoverMessage(format(messages.viewButton()),
                        format(messages.viewHover()),
                        "/" + commandLabel));
    }

    private String pendingDigest(Player player) {
        StringBuilder digest = new StringBuilder(currentVersion);
        pendingPluginFor(player).forEach(release -> digest.append('|').append(release.version()));
        pendingContentFor(player).forEach((content, releases) -> {
            digest.append('|').append(content.slug);
            releases.forEach(release -> digest.append(':').append(release.version()));
        });
        return digest.toString();
    }

    private void open(Player player) {
        List<NightbreakAccount.PluginChangelog> pending = pendingPluginFor(player);
        Map<TrackedContent, List<NightbreakAccount.PluginChangelog>> pendingContent = pendingContentFor(player);
        if (pending.isEmpty() && pendingContent.isEmpty()) {
            player.sendMessage(format(messages.noUnread()));
            return;
        }

        PendingView view = buildPendingView(pending, pendingContent);
        if (VersionChecker.serverVersionOlderThan(21, 6)) {
            openChatFallback(player, view);
            return;
        }

        DialogManager.MultiActionDialogBuilder dialog = new DialogManager.MultiActionDialogBuilder()
                .title(format(messages.dialogTitle()))
                .externalTitle(format(messages.dialogExternalTitle()))
                .columns(1)
                .afterAction(DialogManager.AfterAction.CLOSE)
                .canCloseWithEscape(true);
        if (view.omitted > 0) dialog.addBody(DialogManager.PlainMessageBody.of(
                format(messages.omittedDialog(), "$count", Integer.toString(view.omitted))).width(420));
        for (RenderedRelease release : view.releases)
            dialog.addBody(DialogManager.PlainMessageBody.of(releaseBody(release)).width(420));
        dialog.addAction(DialogManager.ActionButton.of(format(messages.dismissButton()),
                new DialogManager.RunCommandAction("/" + commandLabel + " dismiss")).width(205));
        dialog.addAction(DialogManager.ActionButton.of(format(messages.disableButton()),
                new DialogManager.RunCommandAction("/" + commandLabel + " disable"))
                .tooltip(format(messages.disableTooltip())).width(205));
        DialogManager.sendDialog(player, dialog);
    }

    private PendingView buildPendingView(
            List<NightbreakAccount.PluginChangelog> pluginReleases,
            Map<TrackedContent, List<NightbreakAccount.PluginChangelog>> contentReleases) {
        int total = pluginReleases.size() + contentReleases.values().stream().mapToInt(List::size).sum();
        List<PendingSection> sections = new ArrayList<>();
        if (!pluginReleases.isEmpty()) sections.add(new PendingSection("@plugin", spec.displayName(), pluginReleases));
        contentReleases.forEach((content, releases) -> {
            if (!releases.isEmpty()) sections.add(new PendingSection(content.slug, content.displayName, releases));
        });
        List<RenderedRelease> rendered = new ArrayList<>();
        for (int depth = 1; rendered.size() < MAX_DIALOG_RELEASES; depth++) {
            boolean added = false;
            for (PendingSection section : sections) {
                int index = section.releases.size() - depth;
                if (index < 0) continue;
                rendered.add(new RenderedRelease(section.componentKey, section.componentName,
                        section.releases.get(index)));
                added = true;
                if (rendered.size() >= MAX_DIALOG_RELEASES) break;
            }
            if (!added) break;
        }
        return new PendingView(List.copyOf(rendered), Math.max(0, total - rendered.size()));
    }

    private JsonElement releaseBody(RenderedRelease rendered) {
        NightbreakAccount.PluginChangelog release = rendered.release;
        JsonArray components = new JsonArray();
        JsonObject header = new JsonObject();
        header.addProperty("text", formattedReleaseHeader(rendered));
        header.addProperty("color", "gold");
        header.addProperty("bold", true);
        components.add(header);
        String body = displayReleaseText(rendered);
        String[] lines = body.split("\n", -1);
        for (int index = 0; index < lines.length; index++) {
            if (index > 0) components.add(literalComponent("\n", null));
            String line = lines[index];
            String tagColor = tagColor(line);
            if (tagColor == null) components.add(literalComponent(line, "white"));
            else {
                String tag = line.substring(0, line.indexOf(']') + 1);
                components.add(literalComponent(tag, tagColor));
                components.add(literalComponent(line.substring(tag.length()), "white"));
            }
        }
        return components;
    }

    private static JsonObject literalComponent(String text, String color) {
        JsonObject component = new JsonObject();
        component.addProperty("text", text);
        if (color != null) component.addProperty("color", color);
        return component;
    }

    private static String tagColor(String line) {
        String tagged = line.startsWith("- ") || line.startsWith("* ") ? line.substring(2) : line;
        if (tagged.startsWith("[New]")) return "green";
        if (tagged.startsWith("[Fix]")) return "red";
        if (tagged.startsWith("[Hotfix]")) return "dark_red";
        if (tagged.startsWith("[Tweak]")) return "yellow";
        return null;
    }

    private static String displayReleaseText(RenderedRelease rendered) {
        String body = sanitizeRemoteText(rendered.release.changelog());
        int newline = body.indexOf('\n');
        String firstLine = (newline < 0 ? body : body.substring(0, newline)).trim();
        String expectedHeader = (rendered.componentName + " " + rendered.release.version()).trim();
        if (firstLine.endsWith(":")) firstLine = firstLine.substring(0, firstLine.length() - 1).trim();
        if (firstLine.equalsIgnoreCase(expectedHeader))
            return newline < 0 ? "" : body.substring(newline + 1).stripLeading();
        return body;
    }

    private static String sanitizeRemoteText(String changelog) {
        String body = changelog == null ? "" : changelog.replace("\r", "").trim();
        body = body.replaceAll("(?i)§[0-9A-FK-ORX]", "");
        body = body.replaceAll("[\\p{Cntrl}&&[^\n\t]]", "");
        if (body.length() > MAX_RELEASE_TEXT_LENGTH)
            body = body.substring(0, MAX_RELEASE_TEXT_LENGTH) + "\n…";
        return body;
    }

    private String formattedReleaseHeader(RenderedRelease rendered) {
        String formatted = format(messages.releaseHeader(),
                "$component", sanitizeHeaderValue(rendered.componentName),
                "$version", sanitizeHeaderValue(rendered.release.version()));
        return formatted.replaceAll("(?i)[§&][0-9A-FK-ORX]", "");
    }

    private static String sanitizeHeaderValue(String value) {
        if (value == null) return "";
        return value.replace('\r', ' ')
                .replace('\n', ' ')
                .replaceAll("(?i)[§&][0-9A-FK-ORX]", "")
                .replaceAll("[\\p{Cntrl}]", "")
                .trim();
    }

    private void openChatFallback(Player player, PendingView view) {
        player.sendMessage(format(messages.chatHeader()));
        if (view.omitted > 0)
            player.sendMessage(format(messages.omittedChat(), "$count", Integer.toString(view.omitted)));
        for (RenderedRelease rendered : view.releases) {
            player.sendMessage("§6§l" + formattedReleaseHeader(rendered).stripTrailing());
            for (String line : displayReleaseText(rendered).split("\n", -1))
                player.sendMessage("§f" + line);
        }
        player.sendMessage(format(messages.chatInstructions()));
    }

    private boolean hasPending(Player player) {
        return !pendingPluginFor(player).isEmpty() || !pendingContentFor(player).isEmpty();
    }

    private List<NightbreakAccount.PluginChangelog> pendingPluginFor(Player player) {
        if (trackedPluginReleases.isEmpty()) return List.of();
        List<NightbreakAccount.PluginChangelog> readableReleases = trackedPluginReleases.stream()
                .filter(NightbreakChangelogManager::hasChangelogText)
                .toList();
        if (readableReleases.isEmpty()) return List.of();
        String path = playerPath(player) + ".dismissedPluginVersions";
        List<String> dismissedVersions = state.getStringList(path);
        String dismissedThrough = normalizeVersion(state.getString(playerPath(player) + ".dismissed.plugin"));
        List<NightbreakAccount.PluginChangelog> pending = readableReleases;
        if (dismissedThrough != null) {
            int dismissedIndex = indexOfVersion(pending, dismissedThrough);
            if (dismissedIndex < 0 && sameVersion(dismissedThrough, currentVersion)) pending = List.of();
            else if (dismissedIndex >= 0) pending = dismissedIndex + 1 >= pending.size()
                    ? List.of()
                    : pending.subList(dismissedIndex + 1, pending.size());
        }
        return dismissedVersions.isEmpty() ? pending : filterDismissed(pending, dismissedVersions);
    }

    private Map<TrackedContent, List<NightbreakAccount.PluginChangelog>> pendingContentFor(Player player) {
        Map<TrackedContent, List<NightbreakAccount.PluginChangelog>> result = new LinkedHashMap<>();
        for (TrackedContent content : trackedContent) {
            List<NightbreakAccount.PluginChangelog> releases = trackedContentReleases.get(content.slug);
            if (releases == null || releases.isEmpty()) continue;
            releases = releases.stream().filter(NightbreakChangelogManager::hasChangelogText).toList();
            if (releases.isEmpty()) continue;
            List<String> dismissedVersions = state.getStringList(playerPath(player)
                    + ".dismissedContentVersions." + stateKey(content.slug));
            String dismissed = normalizeVersion(state.getString(playerPath(player) + ".dismissed.content." + stateKey(content.slug)));
            int dismissedIndex = dismissed == null ? -1 : indexOfVersion(releases, dismissed);
            List<NightbreakAccount.PluginChangelog> pending;
            if (dismissed == null) pending = releases;
            else if (dismissedIndex < 0 && sameVersion(dismissed, content.currentVersion)) pending = List.of();
            else if (dismissedIndex < 0) pending = releases;
            else if (dismissedIndex + 1 >= releases.size()) pending = List.of();
            else pending = releases.subList(dismissedIndex + 1, releases.size());
            if (!dismissedVersions.isEmpty()) pending = filterDismissed(pending, dismissedVersions);
            if (!pending.isEmpty()) result.put(content, pending);
        }
        return result;
    }

    private static boolean hasChangelogText(NightbreakAccount.PluginChangelog release) {
        return release.changelog() != null && !release.changelog().isBlank();
    }

    private static List<NightbreakAccount.PluginChangelog> filterDismissed(
            List<NightbreakAccount.PluginChangelog> releases, List<String> dismissedVersions) {
        return releases.stream()
                .filter(release -> dismissedVersions.stream()
                        .noneMatch(version -> sameVersion(version, release.version())))
                .toList();
    }

    private void dismiss(Player player) {
        PendingView displayed = buildPendingView(pendingPluginFor(player), pendingContentFor(player));
        List<NightbreakAccount.PluginChangelog> displayedPlugin = displayed.releases.stream()
                .filter(release -> release.componentKey.equals("@plugin"))
                .map(RenderedRelease::release)
                .toList();
        state.set(playerPath(player) + ".dismissedPluginVersions",
                mergeDismissedVersions(state.getStringList(playerPath(player) + ".dismissedPluginVersions"),
                        displayedPlugin));
        for (TrackedContent content : trackedContent) {
            String path = playerPath(player) + ".dismissedContentVersions." + stateKey(content.slug);
            List<NightbreakAccount.PluginChangelog> displayedContent = displayed.releases.stream()
                    .filter(release -> release.componentKey.equals(content.slug))
                    .map(RenderedRelease::release)
                    .toList();
            state.set(path, mergeDismissedVersions(state.getStringList(path),
                    displayedContent));
        }
        saveState();
        if (hasPending(player)) {
            player.spigot().sendMessage(
                    SpigotMessage.simpleMessage(format(messages.dismissedPage())),
                    SpigotMessage.commandHoverMessage(format(messages.viewNextButton()),
                            format(messages.viewNextHover()),
                            "/" + commandLabel));
        } else {
            player.sendMessage(format(messages.dismissedAll()));
        }
    }

    private static List<String> mergeDismissedVersions(
            List<String> existing, List<NightbreakAccount.PluginChangelog> releases) {
        List<String> merged = new ArrayList<>(existing);
        for (NightbreakAccount.PluginChangelog release : releases)
            if (merged.stream().noneMatch(version -> sameVersion(version, release.version())))
                merged.add(release.version());
        return List.copyOf(merged.subList(Math.max(0, merged.size() - 200), merged.size()));
    }

    private void disable(Player player) {
        state.set(playerPath(player) + ".disabled", true);
        state.set(playerPath(player) + ".dismissed.plugin", currentVersion);
        for (TrackedContent content : trackedContent)
            state.set(playerPath(player) + ".dismissed.content." + stateKey(content.slug), content.currentVersion);
        saveState();
        player.sendMessage(format(messages.disabled()));
    }

    private boolean isPermanentlyDisabled(Player player) {
        return state.getBoolean(playerPath(player) + ".disabled", false);
    }

    private String playerPath(Player player) {
        return "players." + player.getUniqueId();
    }

    private boolean isAdmin(Player player) {
        String wildcard = plugin.getName().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "") + ".*";
        return player.isOp()
                || hasPermission(player, wildcard)
                || hasPermission(player, spec.adminPermission())
                || hasPermission(player, spec.setupPermission())
                || hasPermission(player, spec.initializePermission());
    }

    private static boolean hasPermission(Player player, String permission) {
        return permission != null && !permission.isBlank() && player.hasPermission(permission);
    }

    private void registerCommand() {
        try {
            Field field = Bukkit.getServer().getClass().getDeclaredField("commandMap");
            field.setAccessible(true);
            SimpleCommandMap commandMap = (SimpleCommandMap) field.get(Bukkit.getServer());
            String desiredLabel = commandLabel;
            String defaultFallbackPrefix = sanitizeCommandLabel(plugin.getName());

            RegisteredChangelogCommand existing = findExistingChangelogCommand(
                    commandMap, desiredLabel, defaultFallbackPrefix);
            if (existing != null) {
                commandLabel = existing.label();
                existing.command().reconfigure(
                        format(messages.commandDescription()),
                        format(messages.commandUsage()),
                        format(messages.unavailable()));
                existing.command().register(commandMap);
                registeredCommand = existing.command();
                registeredCommandMap = commandMap;
                return;
            }

            String fallbackPrefix = firstAvailableFallbackPrefix(
                    commandMap, defaultFallbackPrefix, desiredLabel);
            if (fallbackPrefix == null) {
                plugin.getLogger().warning("Could not register /" + desiredLabel
                        + ": no collision-free namespaced label was available.");
                return;
            }
            String actualLabel = commandMap.getCommand(desiredLabel) == null
                    ? desiredLabel
                    : fallbackPrefix + ":" + desiredLabel;
            commandLabel = actualLabel;
            ChangelogCommand candidate = new ChangelogCommand(
                    desiredLabel,
                    format(messages.commandDescription()),
                    format(messages.commandUsage()),
                    format(messages.unavailable()));
            commandMap.register(fallbackPrefix, candidate);
            if (commandMap.getCommand(actualLabel) != candidate) {
                plugin.getLogger().warning("Could not register /" + actualLabel + ".");
                commandLabel = desiredLabel;
                return;
            }
            registeredCommand = candidate;
            registeredCommandMap = commandMap;
        } catch (ReflectiveOperationException exception) {
            plugin.getLogger().warning("Could not register /" + commandLabel + ": " + exception.getMessage());
        }
    }

    private RegisteredChangelogCommand findExistingChangelogCommand(
            SimpleCommandMap commandMap, String desiredLabel, String defaultFallbackPrefix) {
        Command primary = commandMap.getCommand(desiredLabel);
        if (primary instanceof ChangelogCommand changelogCommand)
            return new RegisteredChangelogCommand(desiredLabel, changelogCommand);

        String defaultNamespacedLabel = defaultFallbackPrefix + ":" + desiredLabel;
        Command defaultNamespaced = commandMap.getCommand(defaultNamespacedLabel);
        if (defaultNamespaced instanceof ChangelogCommand changelogCommand)
            return new RegisteredChangelogCommand(defaultNamespacedLabel, changelogCommand);

        try {
            Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);
            return knownCommands.entrySet().stream()
                    .filter(entry -> entry.getKey().endsWith(":" + desiredLabel))
                    .filter(entry -> entry.getValue() instanceof ChangelogCommand)
                    .map(entry -> new RegisteredChangelogCommand(
                            entry.getKey(), (ChangelogCommand) entry.getValue()))
                    .findFirst()
                    .orElse(null);
        } catch (ReflectiveOperationException ignored) {
            return null;
        }
    }

    private static String firstAvailableFallbackPrefix(
            SimpleCommandMap commandMap, String basePrefix, String desiredLabel) {
        for (int suffix = 0; suffix < 100; suffix++) {
            String candidate = suffix == 0 ? basePrefix : basePrefix + suffix;
            if (commandMap.getCommand(candidate + ":" + desiredLabel) == null) return candidate;
        }
        return null;
    }

    private void unregisterCommand() {
        if (registeredCommand == null || registeredCommandMap == null) return;
        ChangelogCommand command = registeredCommand;
        SimpleCommandMap commandMap = registeredCommandMap;
        try {
            command.unregister(commandMap);
            Field knownCommandsField = SimpleCommandMap.class.getDeclaredField("knownCommands");
            knownCommandsField.setAccessible(true);
            @SuppressWarnings("unchecked")
            Map<String, Command> knownCommands = (Map<String, Command>) knownCommandsField.get(commandMap);
            List<String> registeredLabels = new ArrayList<>();
            knownCommands.forEach((label, registered) -> {
                if (registered == command) registeredLabels.add(label);
            });
            registeredLabels.forEach(label -> knownCommands.remove(label, command));
        } catch (ReflectiveOperationException | UnsupportedOperationException exception) {
            plugin.getLogger().warning("Could not fully unregister /" + commandLabel + ": " + exception.getMessage());
        } finally {
            registeredCommand = null;
            registeredCommandMap = null;
        }
    }

    private void saveState() {
        try {
            if (!plugin.getDataFolder().isDirectory() && !plugin.getDataFolder().mkdirs())
                throw new IOException("could not create plugin data folder");
            state.save(stateFile);
        } catch (IOException exception) {
            plugin.getLogger().warning("Could not save changelog state: " + exception.getMessage());
        }
    }

    private List<NightbreakAccount.PluginChangelog> readCachedReleases(String path, String targetVersion) {
        if (!sameVersion(state.getString(path + ".targetVersion"), targetVersion)) return List.of();
        List<NightbreakAccount.PluginChangelog> releases = new ArrayList<>();
        for (Map<?, ?> entry : state.getMapList(path + ".releases")) {
            Object version = entry.get("version");
            Object changelog = entry.get("changelog");
            if (version == null || changelog == null) continue;
            Object releasedAt = entry.get("releasedAt");
            String boundedChangelog = changelog.toString();
            if (boundedChangelog.length() > 12_000)
                boundedChangelog = boundedChangelog.substring(0, 12_000);
            releases.add(new NightbreakAccount.PluginChangelog(
                    version.toString(), boundedChangelog, releasedAt == null ? null : releasedAt.toString()));
            if (releases.size() >= 100) break;
        }
        return List.copyOf(releases);
    }

    private void writeCachedReleases(String path, String targetVersion,
                                     List<NightbreakAccount.PluginChangelog> releases,
                                     boolean complete) {
        List<Map<String, String>> serialized = new ArrayList<>();
        for (NightbreakAccount.PluginChangelog release : releases) {
            Map<String, String> entry = new LinkedHashMap<>();
            entry.put("version", release.version());
            String changelog = release.changelog();
            entry.put("changelog", changelog.length() > 12_000 ? changelog.substring(0, 12_000) : changelog);
            if (release.releasedAt() != null) entry.put("releasedAt", release.releasedAt());
            serialized.add(entry);
        }
        state.set(path + ".targetVersion", targetVersion);
        state.set(path + ".releases", serialized);
        state.set(path + ".complete", complete);
    }

    private static int indexOfVersion(List<NightbreakAccount.PluginChangelog> releases, String version) {
        for (int index = 0; index < releases.size(); index++)
            if (sameVersion(releases.get(index).version(), version)) return index;
        return -1;
    }

    private static String normalizeVersion(String version) {
        if (version == null || version.isBlank()) return null;
        return version.trim().replaceFirst("^[vV]", "");
    }

    private String format(String template, String... replacements) {
        String pluginName = spec.displayName() == null ? "" : spec.displayName();
        String prefix = messages.prefix()
                .replace("$plugin", pluginName)
                .replace("$command", commandLabel);
        String formatted = (template == null ? "" : template)
                .replace("$plugin", pluginName)
                .replace("$command", commandLabel)
                .replace("$prefix", prefix);
        for (int index = 0; index + 1 < replacements.length; index += 2)
            formatted = formatted.replace(replacements[index], replacements[index + 1] == null ? "" : replacements[index + 1]);
        return formatted;
    }

    private static boolean sameVersion(String left, String right) {
        String normalizedLeft = normalizeVersion(left);
        String normalizedRight = normalizeVersion(right);
        return normalizedLeft != null && normalizedLeft.equalsIgnoreCase(normalizedRight);
    }

    private static String sanitizeCommandLabel(String rootCommand) {
        String value = rootCommand == null ? "nightbreak" : rootCommand.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]", "");
        return value.isBlank() ? "nightbreak" : value;
    }

    private static String stateKey(String slug) {
        return slug.toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9_-]", "_");
    }

    private record TrackedContent(String slug, String displayName, String currentVersion,
                                  String floorVersion, boolean bootstrapCurrentRelease) {
    }

    private record RangeSelection(List<NightbreakAccount.PluginChangelog> releases, boolean complete) {
    }

    private record PendingSection(String componentKey, String componentName,
                                  List<NightbreakAccount.PluginChangelog> releases) {
    }

    private record RenderedRelease(String componentKey, String componentName,
                                   NightbreakAccount.PluginChangelog release) {
    }

    private record PendingView(List<RenderedRelease> releases, int omitted) {
    }

    private record RegisteredChangelogCommand(String label, ChangelogCommand command) {
    }

    private static final class ChangelogCommand extends Command {
        private String unavailableMessage;

        private ChangelogCommand(String name, String description, String usage, String unavailableMessage) {
            super(name, description, usage, List.of());
            this.unavailableMessage = unavailableMessage;
        }

        private void reconfigure(String description, String usage, String unavailableMessage) {
            setDescription(description);
            setUsage(usage);
            this.unavailableMessage = unavailableMessage;
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            NightbreakChangelogManager manager = active;
            if (manager == null || manager.closed) {
                sender.sendMessage(manager == null
                        ? unavailableMessage
                        : manager.format(manager.messages.unavailable()));
                return true;
            }
            if (!(sender instanceof Player player) || !manager.isAdmin(player)) {
                sender.sendMessage(manager.format(manager.messages.adminOnly()));
                return true;
            }
            if (args.length == 0) manager.open(player);
            else if (args[0].equalsIgnoreCase("dismiss")) manager.dismiss(player);
            else if (args[0].equalsIgnoreCase("disable")) manager.disable(player);
            else sender.sendMessage(getUsage());
            return true;
        }
    }
}
