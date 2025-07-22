package com.magmaguy.magmacore.menus;

import com.magmaguy.magmacore.MagmaCore;
import lombok.Getter;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.enchantments.Enchantment;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.scheduler.BukkitRunnable;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class SetupMenu {
    private static final int nextIcon = 8;
    private static final int infoIcon = 1;
    private static final int removeFilterIcon = 7;
    public static Map<Inventory, SetupMenu> setupMenus = new HashMap<>();
    private final int previousIcon = 0;
    private final ArrayList<Integer> validSlots = new ArrayList<>(List.of(
            9, 10, 11, 12, 13, 14, 15, 16, 17,
            18, 19, 20, 21, 22, 23, 24, 25, 26,
            27, 28, 29, 30, 31, 32, 33, 34, 35,
            36, 37, 38, 39, 40, 41, 42, 43, 44,
            45, 46, 47, 48, 49, 50, 51, 52, 53));
    private final HashMap<Integer, ContentPackage> contentMap = new HashMap<>();
    private final HashMap<Integer, MenuButton> inventoryMap = new HashMap<>();
    private final Player player;
    private final MenuButton infoButton;
    private final List<? extends ContentPackage> contentPackages;
    private final List<SetupMenuFilter> filterList;
    private final List<Integer> filterSlots = List.of(2, 3, 4, 5, 6);
    private final Map<Integer, List<? extends ContentPackage>> filterMap = new HashMap();
    private Inventory inventory;
    @Getter
    private int currentPage = 1;
    private List<? extends ContentPackage> displayedContentPackages = new ArrayList<>();

    public SetupMenu(Player player,
                     MenuButton infoButton,
                     List<? extends ContentPackage> mainContentList,
                     List<SetupMenuFilter> filterList) {
        this.inventory = Bukkit.createInventory(player, 54, "Setup menu");
        this.player = player;
        this.contentPackages = mainContentList;
        this.displayedContentPackages = contentPackages;
        this.filterList = filterList;
        this.infoButton = infoButton;
        this.redrawMenu(1, this.inventory);
        setupMenus.put(this.inventory, this);
    }

    private void redrawMenu(int page, Inventory inventory) {
        currentPage = page;
        setupMenus.remove(inventory);
        this.inventory = inventory;
        inventory.clear();
        inventoryMap.clear();
        populateNavigationElement();
        populateFilterElements();
        populateContentPackage();
        player.openInventory(inventory);
        setupMenus.put(inventory, this);
    }

    private void populateNavigationElement() {
        inventory.setItem(infoIcon, infoButton.getItemStack());
        inventoryMap.put(infoIcon, infoButton);
        if (currentPage > 1) {
            MenuButton previousButton = new MenuButton() {
                @Override
                public void onClick(Player player) {
                    redrawMenu(getCurrentPage() - 1, inventory);
                }
            };
            inventoryMap.put(previousIcon, previousButton);
            inventory.setItem(previousIcon, previousButton.getItemStack());
        }
        if (currentPage < displayedContentPackages.size() / (double) validSlots.size()) {
            MenuButton nextButton = new MenuButton() {
                @Override
                public void onClick(Player player) {
                    redrawMenu(getCurrentPage() + 1, inventory);
                }
            };
            inventoryMap.put(nextIcon, nextButton);
            inventory.setItem(nextIcon, nextButton.getItemStack());
        }
        if (filterList.isEmpty() || displayedContentPackages == contentPackages) return;
        MenuButton filterResetButton = new MenuButton(Material.BARRIER, "&6Reset filters", new ArrayList<>()) {
            @Override
            public void onClick(Player player) {
                removeFilters();
            }
        };
        inventoryMap.put(removeFilterIcon, filterResetButton);
        inventory.setItem(removeFilterIcon, filterResetButton.getItemStack());
    }

    private void populateFilterElements() {
        int counter = 0;
        for (SetupMenuFilter setupMenuFilter : filterList) {
            if (counter >= filterSlots.size()) break;
            int finalCounter = counter;
            filterMap.put(filterSlots.get(counter), setupMenuFilter.contentPackageList);
            MenuButton filterButton = new MenuButton(setupMenuFilter.itemStack) {
                @Override
                public void onClick(Player player) {
                    int slot = filterSlots.get(finalCounter);
                    displayedContentPackages = filterMap.get(slot);
                    redrawMenu(currentPage, inventory);
                    new BukkitRunnable() {
                        @Override
                        public void run() {
                            if (inventory.getItem(slot) == null) return;
                            inventory.getItem(slot).addUnsafeEnchantment(Enchantment.CHANNELING, 1);
                        }
                    }.runTaskLater(MagmaCore.getInstance().getRequestingPlugin(), 1);
                }
            };
            inventory.setItem(filterSlots.get(counter), setupMenuFilter.itemStack);
            inventoryMap.put(filterSlots.get(counter), filterButton);
            counter++;
        }
    }

    private void populateContentPackage() {
        int counter = 0;
        for (Integer validSlot : validSlots) {
            int contentPackageIndex = (currentPage - 1) * validSlots.size() + counter;
            if (contentPackageIndex >= displayedContentPackages.size()) break;
            inventory.setItem(validSlot, displayedContentPackages.get(contentPackageIndex).getItemstack());
            contentMap.put(validSlot, displayedContentPackages.get(contentPackageIndex));
            inventoryMap.put(validSlot, displayedContentPackages.get(contentPackageIndex));
            counter++;
        }
    }

    private void removeFilters() {
        displayedContentPackages = contentPackages;
        redrawMenu(currentPage, inventory);
    }

    public record SetupMenuFilter(ItemStack itemStack,
                                  List<? extends ContentPackage> contentPackageList) {
    }

    public static class SetupMenuListeners implements Listener {
        @EventHandler(ignoreCancelled = true)
        public void onInventoryInteraction(InventoryClickEvent event) {
            SetupMenu setupMenu = setupMenus.get(event.getInventory());
            if (setupMenu == null) return;
            event.setCancelled(true);
            Player player = (Player) event.getWhoClicked();
            if (setupMenu.inventoryMap.get(event.getSlot()) == null) return;
            setupMenu.inventoryMap.get(event.getSlot()).onClick(player);
        }

        @EventHandler
        public void onClose(InventoryCloseEvent event) {
            setupMenus.remove(event.getInventory());
        }

    }
}
