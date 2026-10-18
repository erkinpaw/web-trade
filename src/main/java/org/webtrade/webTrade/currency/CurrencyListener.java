package org.webtrade.webTrade.currency;

import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.plugin.Plugin;
import org.webtrade.webTrade.ui.Screens;

/**
 * Депозит: по галочке или при закрытии окна руда уходит на счёт,
 * по отмене всё возвращается игроку.
 */
public final class CurrencyListener implements Listener {

    private final Plugin plugin;
    private final CurrencyService currency;
    private final Screens screens;

    public CurrencyListener(Plugin plugin, CurrencyService currency, Screens screens) {
        this.plugin = plugin;
        this.currency = currency;
        this.screens = screens;
    }

    /** Нижний ряд — интерфейс, класть туда ничего нельзя. */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof DepositGui.Holder holder)) {
            return;
        }
        // Нумерация слотов сквозная: 0..SIZE-1 — окно, дальше идёт инвентарь игрока.
        // Своими вещами он волен распоряжаться как угодно, трогаем только наш нижний ряд.
        int slot = event.getRawSlot();
        if (slot < DepositGui.FREE_SLOTS || slot >= DepositGui.SIZE) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (slot == DepositGui.CANCEL_SLOT) {
            holder.cancel();
        } else if (slot != DepositGui.CONFIRM_SLOT) {
            return;
        }
        // Закрывать инвентарь прямо в обработчике клика нельзя — расчёт будет на закрытии.
        plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof DepositGui.Holder)) {
            return;
        }
        boolean touchesPanel = event.getRawSlots().stream()
                .anyMatch(slot -> slot >= DepositGui.FREE_SLOTS && slot < DepositGui.SIZE);
        if (touchesPanel) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof DepositGui.Holder holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        if (holder.isCancelled()) {
            currency.refundDeposit(player, event.getInventory());
            return;
        }
        double ares = currency.settleDeposit(player, event.getInventory());
        if (ares > 0) {
            plugin.getServer().getScheduler().runTask(plugin,
                    () -> screens.depositResult(player, ares));
        }
    }
}
