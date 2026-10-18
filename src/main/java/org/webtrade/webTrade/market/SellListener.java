package org.webtrade.webTrade.market;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.webtrade.webTrade.ui.Screens;
import org.webtrade.webTrade.ui.Ui;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Сдача ресурсов: по галочке или при закрытии окна торгуемое уходит на биржу,
 * остальное возвращается игроку.
 */
public final class SellListener implements Listener {

    private final Plugin plugin;
    private final MarketManager market;
    private final Screens screens;

    public SellListener(Plugin plugin, MarketManager market, Screens screens) {
        this.plugin = plugin;
        this.market = market;
        this.screens = screens;
    }

    /** Нижний ряд — интерфейс, класть туда ничего нельзя. */
    @EventHandler
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SellGui.Holder)) {
            return;
        }
        // Нумерация слотов сквозная: 0..SIZE-1 — окно, дальше идёт инвентарь игрока.
        // Своими вещами он волен распоряжаться как угодно, трогаем только наш нижний ряд.
        int slot = event.getRawSlot();
        if (slot < SellGui.FREE_SLOTS || slot >= SellGui.SIZE) {
            return;
        }
        event.setCancelled(true);
        if (slot == SellGui.CONFIRM_SLOT && event.getWhoClicked() instanceof Player player) {
            // Закрывать инвентарь прямо в обработчике клика нельзя — расчёт будет на закрытии.
            plugin.getServer().getScheduler().runTask(plugin, () -> player.closeInventory());
        }
    }

    @EventHandler
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof SellGui.Holder)) {
            return;
        }
        boolean touchesPanel = event.getRawSlots().stream()
                .anyMatch(slot -> slot >= SellGui.FREE_SLOTS && slot < SellGui.SIZE);
        if (touchesPanel) {
            event.setCancelled(true);
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SellGui.Holder)) {
            return;
        }
        if (!(event.getPlayer() instanceof Player player)) {
            return;
        }
        Inventory inventory = event.getInventory();

        // Сначала собираем всё содержимое, чтобы одинаковые предметы сдать одной сделкой.
        Map<Material, Integer> offered = new LinkedHashMap<>();
        for (int slot = 0; slot < SellGui.FREE_SLOTS; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null) {
                continue;
            }
            if (isSellable(stack)) {
                offered.merge(stack.getType(), stack.getAmount(), Integer::sum);
            } else {
                returnToPlayer(player, stack);
            }
            inventory.setItem(slot, null);
        }
        if (offered.isEmpty()) {
            return;
        }

        double earned = 0.0;
        int storedTotal = 0;
        for (Map.Entry<Material, Integer> entry : offered.entrySet()) {
            MarketManager.SupplyResult result =
                    market.supply(player.getUniqueId(), player.getName(), entry.getKey(), entry.getValue());
            if (result == null) {
                continue;
            }
            earned += result.paid();
            storedTotal += result.stored();

            Component name = Ui.name(entry.getKey());
            if (result.matched() > 0) {
                player.sendMessage(Component.text("Продано " + result.matched() + " × ", NamedTextColor.GREEN)
                        .append(name)
                        .append(Component.text(" за " + MarketManager.money(result.paid()) + " ар.",
                                NamedTextColor.GREEN)));
            }
            if (result.stored() > 0) {
                player.sendMessage(Component.text(result.stored() + " × ", NamedTextColor.YELLOW)
                        .append(name)
                        .append(Component.text(" — спроса нет, лежит на складе.", NamedTextColor.YELLOW)));
            }
        }

        // Итог показываем диалогом: там же кнопка «Сдать ещё», чтобы не бегать в меню.
        double total = earned;
        int stored = storedTotal;
        plugin.getServer().getScheduler().runTask(plugin,
                () -> screens.sellResult(player, total, stored));
    }

    /** Сдавать можно только чистые торгуемые предметы без модификаций. */
    private boolean isSellable(ItemStack stack) {
        if (!market.isTradeable(stack.getType())) {
            return false;
        }
        // предметы с зачарованием/переименованием/прочей начинкой не принимаем —
        // иначе можно сдать что-то ценное по цене обычного блока
        return !stack.hasItemMeta();
    }

    private void returnToPlayer(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }
}
