package org.webtrade.webTrade.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.webtrade.webTrade.resource.ResourceType;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Каталог ресурсов: набираем будущий контейнер. Позиций под тысячу, поэтому
 * это сундук с листалкой и поиском, а количество спрашиваем отдельным диалогом.
 */
public final class CatalogGui extends PagedGui {

    private static final int SLOT_SEARCH = 47;
    private static final int SLOT_CART = 48;
    private static final int SLOT_CLEAR = 51;

    private final Screens screens;
    private final Cart cart;
    private String filter = "";

    public CatalogGui(Screens screens, Cart cart) {
        this.screens = screens;
        this.cart = cart;
    }

    /** Поиск идёт и по русскому названию, и по имени материала вроде STONE. */
    public void filter(String query) {
        this.filter = query == null ? "" : query.trim().toLowerCase(Locale.ROOT);
        refresh();
    }

    public String filterText() {
        return filter;
    }

    @Override
    protected Component title() {
        return Ui.text("Каталог ресурсов", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected List<Entry> entries() {
        List<Entry> entries = list();
        for (ResourceType type : screens.resources().all()) {
            if (!matches(type.material())) {
                continue;
            }
            entries.add(new Entry(iconFor(type), (player, click) -> {
                if (click.isRightClick()) {
                    cart.remove(type.material());
                    refresh();
                } else {
                    screens.amountDialog(player, type.material());
                }
            }));
        }
        if (entries.isEmpty()) {
            entries.add(Entry.inert(Ui.icon(Material.BARRIER,
                    Ui.text("Ничего не найдено", NamedTextColor.RED),
                    List.of(Ui.text("Запрос: " + filter)))));
        }
        return entries;
    }

    private boolean matches(Material material) {
        if (filter.isEmpty()) {
            return true;
        }
        // Русское название живёт на клиенте, на сервере его нет — ищем по имени материала.
        return material.name().toLowerCase(Locale.ROOT).contains(filter);
    }

    private org.bukkit.inventory.ItemStack iconFor(ResourceType type) {
        int inCart = cart.get(type.material());
        List<Component> lore = new java.util.ArrayList<>(List.of(
                Ui.text("Цена за стак: " + Ui.money(type.pricePerStack()) + " ар", NamedTextColor.GOLD),
                Ui.text("Цена за штуку: " + Ui.money(type.pricePerUnit()) + " ар")));
        if (inCart > 0) {
            lore.add(Component.empty());
            lore.add(Ui.text("В контейнере: " + inCart + " шт.", NamedTextColor.GREEN));
            lore.add(Ui.text("На сумму: " + Ui.money(type.pricePerUnit() * inCart) + " ар",
                    NamedTextColor.GREEN));
        }
        lore.add(Component.empty());
        lore.add(Ui.text("ЛКМ — указать количество", NamedTextColor.YELLOW));
        if (inCart > 0) {
            lore.add(Ui.text("ПКМ — убрать из контейнера", NamedTextColor.YELLOW));
        }
        return Ui.icon(type.material(), Math.max(1, Math.min(inCart, 64)), Ui.name(type.material()), lore);
    }

    @Override
    protected Map<Integer, Entry> footer() {
        double total = screens.market().price(cart.lines());
        List<Component> cartLore = cart.isEmpty()
                ? List.of(Ui.text("Пока пусто — выберите ресурсы слева."))
                : List.of(
                        Ui.text("Позиций: " + cart.size(), NamedTextColor.WHITE),
                        Ui.text("Итого: " + Ui.money(total) + " ар", NamedTextColor.GOLD),
                        Component.empty(),
                        Ui.text("Нажмите, чтобы проверить и оплатить.", NamedTextColor.YELLOW));

        return Map.of(
                SLOT_SEARCH, new Entry(
                        Ui.icon(Material.SPYGLASS, Ui.text("Поиск", NamedTextColor.AQUA), List.of(
                                Ui.text(filter.isEmpty() ? "Показаны все ресурсы" : "Запрос: " + filter),
                                Ui.text("Нажмите, чтобы задать запрос.", NamedTextColor.YELLOW))),
                        (player, click) -> screens.searchDialog(player, this)),
                SLOT_CART, new Entry(
                        Ui.icon(Material.CHEST, Ui.text("Контейнер", NamedTextColor.GREEN), cartLore),
                        (player, click) -> {
                            if (cart.isEmpty()) {
                                player.sendMessage(Ui.text("Сначала выберите хотя бы один ресурс.",
                                        NamedTextColor.YELLOW));
                            } else {
                                screens.cartDialog(player);
                            }
                        }),
                SLOT_CLEAR, new Entry(
                        Ui.icon(Material.LAVA_BUCKET, Ui.text("Очистить", NamedTextColor.RED),
                                List.of(Ui.text("Убрать все выбранные ресурсы."))),
                        (player, click) -> {
                            cart.clear();
                            refresh();
                        }));
    }

    @Override
    protected Component backLabel() {
        return Ui.text("← В меню", NamedTextColor.YELLOW);
    }

    @Override
    protected void onBack(Player player) {
        screens.mainMenu(player);
    }
}
