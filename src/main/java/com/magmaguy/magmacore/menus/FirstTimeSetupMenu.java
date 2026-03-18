package com.magmaguy.magmacore.menus;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

public class FirstTimeSetupMenu {

    private final List<Integer> archetypeSlots = List.of(11, 15, 13);
    private final JavaPlugin ownerPlugin;
    private final Player player;
    private final List<MenuButton> menuItems;
    private final MenuButton infoItem;

    public FirstTimeSetupMenu(Player player, String title, String subtitle, MenuButton infoButton, List<MenuButton> menuButtons) {
        this(MagmaCore.getInstance().getRequestingPlugin(), player, title, subtitle, infoButton, menuButtons);
    }

    public FirstTimeSetupMenu(JavaPlugin ownerPlugin,
                              Player player,
                              String title,
                              String subtitle,
                              MenuButton infoButton,
                              List<MenuButton> menuButtons) {
        this.ownerPlugin = ownerPlugin;
        animateTitle(player, title, subtitle);
        this.player = player;
        this.menuItems = menuButtons;
        this.infoItem = infoButton;
    }

    private void createMenu() {
        AdvancedMenu advancedMenu = new AdvancedMenu(player, menuItems.isEmpty() ? 9 : 18);
        advancedMenu.addAdvancedMenuItem(4, infoItem);
        for (int i = 0; i < menuItems.size(); i++)
            advancedMenu.addAdvancedMenuItem(archetypeSlots.get(i), menuItems.get(i));
        advancedMenu.openInventory(player);
    }

    private void animateTitle(Player player, String title, String subtitle) {
        int titleVisible = visibleLength(title);
        int subtitleVisible = visibleLength(subtitle);
        new BukkitRunnable() {
            int counter = 0;

            @Override
            public void run() {
                counter++;
                if (counter > titleVisible + subtitleVisible + 30) {
                    cancel();
                    createMenu();
                    return;
                }
                String titleSequence = ChatColorConverter.convert(visibleSubstring(title, Math.min(counter, titleVisible)));
                int subtitleCounter = Math.min(counter - titleVisible, subtitleVisible);
                String subtitleSequence = "";
                if (subtitleCounter > 0) {
                    subtitleSequence = ChatColorConverter.convert(visibleSubstring(subtitle, subtitleCounter));
                }
                player.sendTitle(titleSequence, subtitleSequence, 0, 2, 2);
            }
        }.runTaskTimer(ownerPlugin, 0, 1);
    }

    private static int visibleLength(String text) {
        int count = 0;
        int i = 0;
        while (i < text.length()) {
            // Gradient tags: <g:...> or <gradient:...>
            if (text.charAt(i) == '<' && (text.startsWith("<g:", i) || text.startsWith("<gradient:", i))) {
                int close = text.indexOf('>', i);
                if (close != -1) { i = close + 1; continue; }
            }
            // Gradient close tags: </g> or </gradient>
            if (text.charAt(i) == '<' && (text.startsWith("</g>", i) || text.startsWith("</gradient>", i))) {
                i += text.startsWith("</g>", i) ? 4 : 11;
                continue;
            }
            // Hex color <#RRGGBB>
            if (text.charAt(i) == '<' && text.startsWith("<#", i)) {
                int close = text.indexOf('>', i);
                if (close != -1) { i = close + 1; continue; }
            }
            // Legacy color codes &X or &#RRGGBB
            if (text.charAt(i) == '&' && i + 1 < text.length()) {
                if (text.charAt(i + 1) == '#' && i + 8 <= text.length()) { i += 8; continue; }
                i += 2;
                continue;
            }
            count++;
            i++;
        }
        return count;
    }

    private static String visibleSubstring(String text, int visibleCount) {
        StringBuilder result = new StringBuilder();
        int visible = 0;
        int i = 0;
        boolean inGradient = false;
        String gradientCloseTag = null;

        while (i < text.length() && visible < visibleCount) {
            // Gradient open tags
            if (text.charAt(i) == '<' && (text.startsWith("<g:", i) || text.startsWith("<gradient:", i))) {
                int close = text.indexOf('>', i);
                if (close != -1) {
                    result.append(text, i, close + 1);
                    inGradient = true;
                    gradientCloseTag = text.contains("</gradient>") ? "</gradient>" : "</g>";
                    i = close + 1;
                    continue;
                }
            }
            // Gradient close tags
            if (text.charAt(i) == '<' && (text.startsWith("</g>", i) || text.startsWith("</gradient>", i))) {
                String closeTag = text.startsWith("</g>", i) ? "</g>" : "</gradient>";
                result.append(closeTag);
                i += closeTag.length();
                inGradient = false;
                gradientCloseTag = null;
                continue;
            }
            // Hex color <#RRGGBB>
            if (text.charAt(i) == '<' && text.startsWith("<#", i)) {
                int close = text.indexOf('>', i);
                if (close != -1) {
                    result.append(text, i, close + 1);
                    i = close + 1;
                    continue;
                }
            }
            // Legacy color codes
            if (text.charAt(i) == '&' && i + 1 < text.length()) {
                if (text.charAt(i + 1) == '#' && i + 8 <= text.length()) {
                    result.append(text, i, i + 8);
                    i += 8;
                    continue;
                }
                result.append(text, i, i + 2);
                i += 2;
                continue;
            }
            // Visible character
            result.append(text.charAt(i));
            visible++;
            i++;
        }

        // Close any open gradient tag so ChatColorConverter gets valid input
        if (inGradient && gradientCloseTag != null) {
            result.append(gradientCloseTag);
        }

        return result.toString();
    }
}
