package org.webtrade.webTrade.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.webtrade.webTrade.resource.ResourceType;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/** Сданное, но ещё не оплаченное: лежит на складе и ждёт, пока появится спрос. */
public final class StockGui extends PagedGui {

    private final Screens screens;
    private final UUID owner;

    public StockGui(Screens screens, UUID owner) {
        this.screens = screens;
        this.owner = owner;
    }

    @Override
    protected Component title() {
        return Ui.text("Мой склад", NamedTextColor.DARK_AQUA);
    }

    @Override
    protected List<Entry> entries() {
        Map<Material, Integer> stock = screens.market().stockOf(owner);
        if (stock.isEmpty()) {
            return List.of(Entry.inert(Ui.icon(Material.BARRIER,
                    Ui.text("На складе пусто", NamedTextColor.RED),
                    List.of(Ui.text("Сюда попадает сданное, на что пока нет спроса.")))));
        }

        List<Entry> entries = list();
        stock.forEach((material, amount) -> {
            ResourceType type = screens.resources().get(material);
            double perStack = type == null ? 0.0 : type.pricePerStack();
            double pending = type == null ? 0.0 : type.pricePerUnit() * amount;
            entries.add(Entry.inert(Ui.icon(material, Ui.name(material), List.of(
                    Ui.text("Лежит: " + amount + " шт.", NamedTextColor.WHITE),
                    Ui.text("Цена за стак: " + Ui.money(perStack) + " ар", NamedTextColor.GOLD),
                    Ui.text("Ожидается к оплате: " + Ui.money(pending) + " ар", NamedTextColor.GREEN),
                    Component.empty(),
                    Ui.text("Оплата придёт сама, как только появится спрос.")))));
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
