package org.webtrade.webTrade.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.webtrade.webTrade.resource.ResourceType;

import java.util.List;
import java.util.Map;

/** Доска заказов: что сейчас нужно игрокам и сколько за это платят. */
public final class DemandGui extends PagedGui {

    private final Screens screens;

    public DemandGui(Screens screens) {
        this.screens = screens;
    }

    @Override
    protected Component title() {
        return Ui.text("Спрос на ресурсы", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected List<Entry> entries() {
        Map<Material, Integer> demand = screens.market().demand();
        if (demand.isEmpty()) {
            return List.of(Entry.inert(Ui.icon(Material.BARRIER,
                    Ui.text("Спроса сейчас нет", NamedTextColor.RED),
                    List.of(Ui.text("Создайте контейнер — и он появится здесь.")))));
        }

        List<Entry> entries = list();
        demand.forEach((material, needed) -> {
            ResourceType type = screens.resources().get(material);
            double perStack = type == null ? 0.0 : type.pricePerStack();
            // Иконку не стакаем: числа на предметах сливаются в кашу, количество читается в подсказке.
            entries.add(Entry.inert(Ui.icon(material, Ui.name(material), List.of(
                    Ui.amount("Нужно: ", needed, NamedTextColor.WHITE),
                    Ui.text("Цена за стак: " + Ui.money(perStack) + " ар", NamedTextColor.GOLD),
                    Component.empty(),
                    Ui.text("Добудьте и сдайте — оплата придёт сразу.")))));
        });
        return entries;
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
