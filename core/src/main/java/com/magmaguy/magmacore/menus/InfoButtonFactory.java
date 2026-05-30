package com.magmaguy.magmacore.menus;

import com.magmaguy.magmacore.nightbreak.NightbreakAccount;
import com.magmaguy.magmacore.util.ItemStackGenerator;
import com.magmaguy.magmacore.util.Logger;
import com.magmaguy.magmacore.util.SpigotMessage;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Factory for the shared "info" {@link MenuButton} that appears at the top of
 * Nightbreak-style setup menus (originally extracted from
 * {@code EliteSetupMenu#createMenu}). Plugins call
 * {@link #nightbreakInfoButton} with their own copy text, links, and the
 * skull-owner username for the button's player-head icon.
 * <p>
 * The button's {@code onClick} closes the inventory and prints a small chat
 * block: a decorative gradient bar, a headline, three labeled hover-link rows
 * (wiki / content / discord), an optional click-to-run command row gated on
 * {@link NightbreakAccount#hasToken()}, and a closing gradient bar.
 */
public final class InfoButtonFactory {

    /**
     * Gradient bar shown above and below the chat block. Lifted verbatim from
     * the original {@code EliteSetupMenu} so the visual output is unchanged.
     */
    private static final String GRADIENT_BAR =
            "<g:#8B0000:#CC4400:#DAA520>" +
                    "▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬▬" +
                    "</g>";

    private InfoButtonFactory() {
    }

    /**
     * Builds the standard Nightbreak info {@link MenuButton}.
     * <p>
     * Pass {@code null} for {@code downloadAllClickMessage} (and/or the other
     * downloadAll arguments) to disable the optional "/em downloadall"-style
     * row entirely; otherwise it is shown only when
     * {@link NightbreakAccount#hasToken()} is true at click time.
     *
     * @param skullName               Mojang username whose skin is used for the player-head icon.
     * @param displayName             Item display name (legacy color codes / MiniMessage gradients allowed).
     * @param displayLore             Item lore lines shown in the tooltip.
     * @param headlineMessage         First chat line printed inside the gradient bars.
     * @param wikiLabel               Plain-text prefix for the wiki row (e.g. "&2&lWiki page: ").
     * @param wikiLinkDisplay         Clickable text for the wiki row.
     * @param wikiLinkHover           Hover tooltip for the wiki row.
     * @param wikiUrl                 URL opened by the wiki row's click event.
     * @param contentLabel            Plain-text prefix for the content row.
     * @param contentLinkDisplay      Clickable text for the content row.
     * @param contentLinkHover        Hover tooltip for the content row.
     * @param contentUrl              URL opened by the content row's click event.
     * @param discordLabel            Plain-text prefix for the discord row.
     * @param discordLinkDisplay      Clickable text for the discord row.
     * @param discordLinkHover        Hover tooltip for the discord row.
     * @param discordUrl              URL opened by the discord row's click event.
     * @param downloadAllClickMessage Clickable text for the optional command row, or {@code null} to omit the row.
     * @param downloadAllClickHover   Hover tooltip for the optional command row.
     * @param downloadAllCommand      Command to run when the optional row is clicked (e.g. "/em downloadall").
     * @return A {@link MenuButton} whose icon is the configured skull and whose
     *         {@code onClick} prints the chat block described above.
     */
    public static MenuButton nightbreakInfoButton(
            String skullName,
            String displayName,
            List<String> displayLore,
            String headlineMessage,
            String wikiLabel,
            String wikiLinkDisplay,
            String wikiLinkHover,
            String wikiUrl,
            String contentLabel,
            String contentLinkDisplay,
            String contentLinkHover,
            String contentUrl,
            String discordLabel,
            String discordLinkDisplay,
            String discordLinkHover,
            String discordUrl,
            String downloadAllClickMessage,
            String downloadAllClickHover,
            String downloadAllCommand) {
        return new MenuButton(ItemStackGenerator.generateSkullItemStack(skullName, displayName, displayLore)) {
            @Override
            public void onClick(Player p) {
                p.closeInventory();
                Logger.sendSimpleMessage(p, GRADIENT_BAR);
                Logger.sendSimpleMessage(p, headlineMessage);
                p.spigot().sendMessage(
                        SpigotMessage.simpleMessage(wikiLabel),
                        SpigotMessage.hoverLinkMessage(wikiLinkDisplay, wikiLinkHover, wikiUrl));
                p.spigot().sendMessage(
                        SpigotMessage.simpleMessage(contentLabel),
                        SpigotMessage.hoverLinkMessage(contentLinkDisplay, contentLinkHover, contentUrl));
                p.spigot().sendMessage(
                        SpigotMessage.simpleMessage(discordLabel),
                        SpigotMessage.hoverLinkMessage(discordLinkDisplay, discordLinkHover, discordUrl));
                if (NightbreakAccount.hasToken() && downloadAllClickMessage != null) {
                    p.spigot().sendMessage(
                            SpigotMessage.commandHoverMessage(
                                    downloadAllClickMessage,
                                    downloadAllClickHover,
                                    downloadAllCommand));
                }
                Logger.sendSimpleMessage(p, GRADIENT_BAR);
            }
        };
    }
}
