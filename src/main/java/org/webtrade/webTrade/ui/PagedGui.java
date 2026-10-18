package org.webtrade.webTrade.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

/**
 * Сундучное меню с листалкой: верхние пять рядов — содержимое, нижний — навигация.
 * Класть предметы сюда нельзя, все клики гасит {@link GuiListener}.
 * <p>
 * Такой список нужен там, где позиций много (каталог, спрос, склад): диалог показывает
 * кнопки в столбик и на сотни строк не рассчитан.
 */
public abstract class PagedGui implements InventoryHolder {

    public static final int SIZE = 54;
    /** Верхние пять рядов отданы под содержимое. */
    public static final int PAGE_SIZE = 45;

    protected static final int SLOT_PREV = 45;
    protected static final int SLOT_BACK = 49;
    protected static final int SLOT_NEXT = 53;

    /** Строка меню: как выглядит и что делает по клику. */
    public record Entry(org.bukkit.inventory.ItemStack icon, BiConsumer<Player, ClickType> onClick) {

        /** Строка только для чтения — клик по ней ничего не делает. */
        public static Entry inert(org.bukkit.inventory.ItemStack icon) {
            return new Entry(icon, (player, click) -> { });
        }
    }

    private Inventory inventory;
    private int page;
    /** Список, который сейчас на экране: по нему же разбираем клик, чтобы он не разъехался. */
    private List<Entry> rendered = List.of();

    protected abstract Component title();

    protected abstract List<Entry> entries();

    /** Дополнительные кнопки нижнего ряда. Ключ — слот 45..53. */
    protected Map<Integer, Entry> footer() {
        return Map.of();
    }

    /** Что делает центральная кнопка нижнего ряда. */
    protected void onBack(Player player) {
        player.closeInventory();
    }

    protected Component backLabel() {
        return Ui.text("Закрыть", NamedTextColor.RED);
    }

    public void open(Player player) {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, SIZE, title());
        }
        render();
        player.openInventory(inventory);
    }

    /** Перерисовать содержимое, не закрывая окно, — после покупки или сдачи ресурсов. */
    public void refresh() {
        if (inventory != null) {
            render();
        }
    }

    private void render() {
        inventory.clear();
        rendered = List.copyOf(entries());

        int pages = Math.max(1, (rendered.size() + PAGE_SIZE - 1) / PAGE_SIZE);
        page = Math.clamp(page, 0, pages - 1);

        int from = page * PAGE_SIZE;
        for (int slot = 0; slot < PAGE_SIZE && from + slot < rendered.size(); slot++) {
            inventory.setItem(slot, rendered.get(from + slot).icon());
        }
        for (int slot = PAGE_SIZE; slot < SIZE; slot++) {
            inventory.setItem(slot, Ui.filler());
        }

        if (page > 0) {
            inventory.setItem(SLOT_PREV, Ui.icon(Material.ARROW,
                    Ui.text("← Назад", NamedTextColor.YELLOW),
                    List.of(Ui.text("Страница " + page + " из " + pages))));
        }
        if (page < pages - 1) {
            inventory.setItem(SLOT_NEXT, Ui.icon(Material.ARROW,
                    Ui.text("Вперёд →", NamedTextColor.YELLOW),
                    List.of(Ui.text("Страница " + (page + 2) + " из " + pages))));
        }
        inventory.setItem(SLOT_BACK, Ui.icon(Material.BARRIER, backLabel(), List.of()));

        footer().forEach((slot, entry) -> {
            if (slot >= PAGE_SIZE && slot < SIZE) {
                inventory.setItem(slot, entry.icon());
            }
        });
    }

    /** Разбор клика. Вызывается только из {@link GuiListener}. */
    void handleClick(Player player, int rawSlot, ClickType click) {
        // Клик по своему инвентарю нас не касается — там игрок волен делать что угодно.
        if (rawSlot < 0 || rawSlot >= SIZE) {
            return;
        }
        if (rawSlot < PAGE_SIZE) {
            int index = page * PAGE_SIZE + rawSlot;
            if (index < rendered.size()) {
                rendered.get(index).onClick().accept(player, click);
            }
            return;
        }
        Entry extra = footer().get(rawSlot);
        if (extra != null) {
            extra.onClick().accept(player, click);
            return;
        }
        switch (rawSlot) {
            case SLOT_PREV -> {
                page--;
                render();
            }
            case SLOT_NEXT -> {
                page++;
                render();
            }
            case SLOT_BACK -> onBack(player);
            default -> { }
        }
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, SIZE, title());
        }
        return inventory;
    }

    /** Удобство для наследников: собрать список строк. */
    protected static List<Entry> list() {
        return new ArrayList<>();
    }
}
