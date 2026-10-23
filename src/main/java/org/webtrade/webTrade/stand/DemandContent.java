package org.webtrade.webTrade.stand;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.webtrade.webTrade.market.MarketManager;
import org.webtrade.webTrade.resource.ResourceRegistry;
import org.webtrade.webTrade.resource.ResourceType;
import org.webtrade.webTrade.ui.Screens;
import org.webtrade.webTrade.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** Доска заказов на стенде: что нужно игрокам и почём. */
public final class DemandContent implements StandContent {

    private final MarketManager market;
    private final ResourceRegistry resources;
    private final Screens screens;

    public DemandContent(MarketManager market, ResourceRegistry resources, Screens screens) {
        this.market = market;
        this.resources = resources;
        this.screens = screens;
    }

    @Override
    public String title() {
        return "Спрос на ресурсы";
    }

    @Override
    public List<Row> rows() {
        List<Row> rows = new ArrayList<>();
        for (Map.Entry<Material, Integer> entry : market.demand().entrySet()) {
            ResourceType type = resources.get(entry.getKey());
            rows.add(new Row(entry.getKey(), entry.getValue(),
                    type == null ? 0.0 : type.pricePerStack()));
        }
        return rows;
    }

    @Override
    public void onMenu(Player player) {
        // Кнопка ведёт в общее меню: оттуда и заказать ресурсы, и внести ары, и забрать контейнер.
        screens.mainMenu(player);
    }
}
