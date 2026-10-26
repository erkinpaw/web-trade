package org.webtrade.webTrade.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
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
    protected static final int SLOT_PAGE = 50;

    /**
     * Сколько пикселей заголовка помещается в сундук: окно шириной 176, текст начинается
     * с отступа 8 и до правого края должен не дойти. Длиннее — наползёт на рамку.
     */
    public static final int TITLE_WIDTH = 160;

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

    /** Где стоит кнопка возврата. Меню с несколькими своими кнопками может её сдвинуть. */
    protected int backSlot() {
        return SLOT_BACK;
    }

    protected Component backLabel() {
        return Ui.text("Закрыть", NamedTextColor.RED);
    }

    public void open(Player player) {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, SIZE, fittedTitle());
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
                    Ui.text("← Предыдущая", NamedTextColor.YELLOW),
                    List.of(Ui.text("Страница " + page + " из " + pages))));
        }
        if (page < pages - 1) {
            inventory.setItem(SLOT_NEXT, Ui.icon(Material.ARROW,
                    Ui.text("Следующая →", NamedTextColor.YELLOW),
                    List.of(Ui.text("Страница " + (page + 2) + " из " + pages))));
        }
        inventory.setItem(backSlot(), Ui.icon(Material.BARRIER, backLabel(), List.of()));
        if (pages > 1) {
            inventory.setItem(SLOT_PAGE, Ui.icon(Material.PAPER, page + 1,
                    Ui.text("Страница " + (page + 1) + " из " + pages, NamedTextColor.WHITE),
                    List.of(Ui.text("Всего позиций: " + rendered.size()))));
        }

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
        if (rawSlot == backSlot()) {
            onBack(player);
        } else if (rawSlot == SLOT_PREV && page > 0) {
            page--;
            render();
        } else if (rawSlot == SLOT_NEXT) {
            page++;
            render();
        }
    }

    /** Заголовок, обрезанный под ширину сундука, — длинное название заказа иначе вылезает за рамку. */
    private Component fittedTitle() {
        Component title = title();
        if (title instanceof TextComponent text && text.children().isEmpty()) {
            return text.content(Ui.fit(text.content(), TITLE_WIDTH));
        }
        return title;
    }

    @Override
    public @NotNull Inventory getInventory() {
        if (inventory == null) {
            inventory = Bukkit.createInventory(this, SIZE, fittedTitle());
        }
        return inventory;
    }

    /** Удобство для наследников: собрать список строк. */
    protected static List<Entry> list() {
        return new ArrayList<>();
    }
}
