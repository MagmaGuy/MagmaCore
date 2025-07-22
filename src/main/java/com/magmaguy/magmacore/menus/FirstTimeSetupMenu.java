package com.magmaguy.magmacore.menus;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.util.ChatColorConverter;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.List;

public class FirstTimeSetupMenu {

    private final List<Integer> archetypeSlots = List.of(11, 15, 13);
    private final Player player;
    private final List<MenuButton> menuItems;
    private final MenuButton infoItem;

    public FirstTimeSetupMenu(Player player, String title, String subtitle, MenuButton infoButton, List<MenuButton> menuButtons) {
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
        new BukkitRunnable() {
            int counter = 0;

            @Override
            public void run() {
                counter++;
                if (counter > title.length() + subtitle.length() + 20 * 3) {
                    cancel();
                    createMenu();
                    return;
                }
                String titleSequence = ChatColorConverter.convert(title.substring(0, Math.min(counter, title.length())));
                int subtitleCounter = Math.min(counter - title.length(), subtitle.length());
                String subtitleSequence = "";
                if (subtitleCounter > 0) {
                    subtitleSequence = ChatColorConverter.convert(subtitle.substring(0, subtitleCounter));
                }
                player.sendTitle(titleSequence, subtitleSequence, 0, 2, 2);
            }
        }.runTaskTimer(MagmaCore.getInstance().getRequestingPlugin(), 0, 1);
    }
}
