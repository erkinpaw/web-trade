package org.webtrade.webTrade.ui;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.player.PlayerQuitEvent;

/**
 * Один обработчик на все листаемые меню: гасит попытки что-то из них вытащить
 * и передаёт клик самому меню.
 */
public final class GuiListener implements Listener {

    private final Screens screens;

    public GuiListener(Screens screens) {
        this.screens = screens;
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        // Неоплаченный черновик контейнера держать смысла нет.
        screens.forget(event.getPlayer().getUniqueId());
    }

    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof PagedGui gui)) {
            return;
        }
        int slot = event.getRawSlot();
        boolean inMenu = slot >= 0 && slot < PagedGui.SIZE;
        // Шифт-клик и двойной клик из своего инвентаря тянут предметы в меню — их тоже гасим.
        boolean movesIntoMenu = event.isShiftClick() || event.getClick() == ClickType.DOUBLE_CLICK;

        if (inMenu || movesIntoMenu) {
            // Меню — витрина: ни забрать, ни положить. Но своими вещами игрок волен
            // распоряжаться свободно, иначе в окне выдачи некуда разложить забранное.
            event.setCancelled(true);
        }
        if (inMenu && event.getWhoClicked() instanceof Player player) {
            gui.handleClick(player, slot, event.getClick());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof PagedGui)) {
            return;
        }
        // Протаскивание мимо обработчика кликов — закрываем и его.
        boolean touchesMenu = event.getRawSlots().stream().anyMatch(slot -> slot < PagedGui.SIZE);
        if (touchesMenu) {
            event.setCancelled(true);
        }
    }
}
