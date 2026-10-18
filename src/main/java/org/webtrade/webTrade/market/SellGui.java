package org.webtrade.webTrade.market;

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
 * Интерфейс сдачи ресурсов: игрок кидает сюда добытое и жмёт галочку.
 * Обработка — в {@link SellListener}.
 * <p>
 * Нижний ряд занят кнопкой и заглушками: так туда не попадут предметы при шифт-клике,
 * и не придётся отличать «сданное» от «интерфейса».
 */
public final class SellGui {

    /** Верхние пять рядов свободны под ресурсы. */
    public static final int FREE_SLOTS = 45;
    public static final int SIZE = 54;
    public static final int CONFIRM_SLOT = 49;

    /** Помечает наш инвентарь продажи. */
    public static final class Holder implements InventoryHolder {
        private Inventory inventory;

        @Override
        public @NotNull Inventory getInventory() {
            return inventory;
        }
    }

    private SellGui() {
    }

    public static void open(Player player) {
        Holder holder = new Holder();
        Inventory inventory = Bukkit.createInventory(holder, SIZE,
                Component.text("Сдать ресурсы", NamedTextColor.GOLD));
        for (int slot = FREE_SLOTS; slot < SIZE; slot++) {
            inventory.setItem(slot, Ui.filler());
        }
        inventory.setItem(CONFIRM_SLOT, Ui.icon(Material.LIME_DYE,
                Ui.text("✔ Сдать и получить оплату", NamedTextColor.GREEN),
                List.of(
                        Ui.text("Всё, что лежит выше, уйдёт на биржу."),
                        Ui.text("Непринимаемое вернётся в инвентарь."),
                        Component.empty(),
                        Ui.text("Закрыть окно — то же самое.", NamedTextColor.DARK_GRAY))));
        holder.inventory = inventory;
        player.openInventory(inventory);
    }
}
