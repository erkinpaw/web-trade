package org.webtrade.webTrade.currency;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.webtrade.webTrade.ui.Ui;

import java.util.List;

/**
 * Интерфейс депозита: игрок кидает сюда руду и подтверждает зачисление.
 * Обработка — в {@link CurrencyListener}.
 * <p>
 * Нижний ряд занят кнопками и заглушками: так туда не попадут предметы при шифт-клике,
 * и не придётся отличать вложенное от интерфейса.
 */
public final class DepositGui {

    /** Верхние три ряда свободны под руду. */
    public static final int FREE_SLOTS = 27;
    public static final int SIZE = 36;
    public static final int CONFIRM_SLOT = 31;
    public static final int CANCEL_SLOT = 29;

    /** Помечает наш инвентарь депозита. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;
        /** Отмена: вернуть всё и ничего не зачислять. */
        private boolean cancelled;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }

        public boolean isCancelled() {
            return cancelled;
        }

        public void cancel() {
            this.cancelled = true;
        }
    }

    private DepositGui() {
    }

    public static void open(Player player) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                Component.text("Внести ары", NamedTextColor.AQUA));
        for (int slot = FREE_SLOTS; slot < SIZE; slot++) {
            inventory.setItem(slot, Ui.filler());
        }
        inventory.setItem(CONFIRM_SLOT, Ui.icon(Material.LIME_DYE,
                Ui.text("✔ Зачислить на счёт", NamedTextColor.GREEN),
                List.of(
                        Ui.text("Руда сверху уйдёт на счёт."),
                        Ui.text("Всё остальное вернётся в инвентарь."),
                        Component.empty(),
                        Ui.text("Закрыть окно — то же самое.", NamedTextColor.DARK_GRAY))));
        inventory.setItem(CANCEL_SLOT, Ui.icon(Material.BARRIER,
                Ui.text("✖ Отмена", NamedTextColor.RED),
                List.of(Ui.text("Забрать всё обратно, ничего не зачисляя."))));
        holder.inventory = inventory;
        player.openInventory(inventory);
    }
}
