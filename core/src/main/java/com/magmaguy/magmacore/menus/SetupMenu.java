package com.magmaguy.magmacore.menus;

import com.magmaguy.magmacore.MagmaCore;
import com.magmaguy.magmacore.util.ItemStackGenerator;
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
import org.bukkit.plugin.java.JavaPlugin;

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
            36, 37, 38, 39, 40, 41, 42, 43, 44));
    private final HashMap<Integer, ContentPackage> contentMap = new HashMap<>();
    private final HashMap<Integer, MenuButton> inventoryMap = new HashMap<>();
    private final Player player;
    private final JavaPlugin ownerPlugin;
    private final MenuButton infoButton;
    private final List<? extends ContentPackage> contentPackages;
    private final List<SetupMenuFilter> filterList;
    private final List<MenuButton> toolbarButtons;
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
        this(MagmaCore.getInstance().getRequestingPlugin(), player, infoButton, mainContentList, filterList, "Setup menu");
    }

    public SetupMenu(Player player,
                     MenuButton infoButton,
                     List<? extends ContentPackage> mainContentList,
                     List<SetupMenuFilter> filterList,
                     String title) {
        this(MagmaCore.getInstance().getRequestingPlugin(), player, infoButton, mainContentList, filterList, title);
    }

    public SetupMenu(JavaPlugin ownerPlugin,
                     Player player,
                     MenuButton infoButton,
                     List<? extends ContentPackage> mainContentList,
                     List<SetupMenuFilter> filterList) {
        this(ownerPlugin, player, infoButton, mainContentList, filterList, "Setup menu");
    }

    public SetupMenu(JavaPlugin ownerPlugin,
                     Player player,
                     MenuButton infoButton,
                     List<? extends ContentPackage> mainContentList,
                     List<SetupMenuFilter> filterList,
                     String title) {
        this(ownerPlugin, player, infoButton, mainContentList, filterList, List.of(), title);
    }

    public SetupMenu(JavaPlugin ownerPlugin,
                     Player player,
                     MenuButton infoButton,
                     List<? extends ContentPackage> mainContentList,
                     List<SetupMenuFilter> filterList,
                     List<MenuButton> toolbarButtons,
                     String title) {
        this.inventory = Bukkit.createInventory(player, 45, title);
        this.player = player;
        this.ownerPlugin = ownerPlugin;
        this.contentPackages = mainContentList;
        this.displayedContentPackages = contentPackages;
        this.filterList = filterList;
        this.toolbarButtons = toolbarButtons == null ? List.of() : List.copyOf(toolbarButtons);
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
        // Only open the container when it isn't already on screen. Reopening an
        // already-open inventory is invisible on Java, but through Geyser it forces
        // a Bedrock container close/reopen handshake that leaves the client bound
        // to a stale window — every tap after that is silently dropped. Slot
        // updates alone reach both platforms fine.
        if (!inventory.equals(player.getOpenInventory().getTopInventory()))
            player.openInventory(inventory);
        setupMenus.put(inventory, this);
    }

    /**
     * Re-renders the setup menu the player currently has open, in place: every
     * button re-derives its state (content packages re-render installed/updatable
     * icons) without the container being reopened. Callers that used to rebuild
     * and reopen a fresh menu after an async refresh broke Bedrock clients — see
     * {@link #redrawMenu}. Returns false when the player is not currently viewing
     * a setup menu, in which case the caller may build a fresh one.
     */
    public static boolean refreshInPlaceFor(Player player) {
        SetupMenu setupMenu = setupMenus.get(player.getOpenInventory().getTopInventory());
        if (setupMenu == null || !setupMenu.player.equals(player)) return false;
        setupMenu.redrawMenu(setupMenu.currentPage, setupMenu.inventory);
        return true;
    }

    private void populateNavigationElement() {
        inventory.setItem(infoIcon, infoButton.getItemStack());
        inventoryMap.put(infoIcon, infoButton);
        // The page arrows need real ItemStacks: the old no-arg MenuButton left the
        // slot empty, which Java clients could still click but Bedrock clients
        // cannot — tapping an empty slot with an empty cursor sends nothing, so
        // multi-page menus were unpageable (and the arrows invisible) on Bedrock.
        if (currentPage > 1) {
            MenuButton previousButton = new MenuButton(
                    ItemStackGenerator.generateItemStack(Material.ARROW, "&fPrevious page", new ArrayList<>())) {
                @Override
                public void onClick(Player player) {
                    redrawMenu(getCurrentPage() - 1, inventory);
                }
            };
            inventoryMap.put(previousIcon, previousButton);
            inventory.setItem(previousIcon, previousButton.getItemStack());
        }
        if (currentPage < displayedContentPackages.size() / (double) validSlots.size()) {
            MenuButton nextButton = new MenuButton(
                    ItemStackGenerator.generateItemStack(Material.ARROW, "&fNext page", new ArrayList<>())) {
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
        filterMap.clear();
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
                    }.runTaskLater(ownerPlugin, 1);
                }
            };
            inventory.setItem(filterSlots.get(counter), setupMenuFilter.itemStack);
            inventoryMap.put(filterSlots.get(counter), filterButton);
            counter++;
        }
        populateToolbarElements(counter);
    }

    private void populateToolbarElements(int counter) {
        for (MenuButton toolbarButton : toolbarButtons) {
            if (counter >= filterSlots.size()) break;
            int slot = filterSlots.get(counter);
            ItemStack itemStack = toolbarButton instanceof ContentPackage contentPackage
                    ? contentPackage.getItemstack()
                    : toolbarButton.getItemStack();
            inventory.setItem(slot, itemStack);
            inventoryMap.put(slot, toolbarButton);
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
