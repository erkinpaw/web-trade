package org.webtrade.webTrade.stand;

import org.bukkit.Material;
import org.bukkit.entity.Player;

import java.util.List;

/**
 * Что стенд показывает и что делает по нажатиям.
 * Вынесено отдельно, чтобы тем же стендом можно было показать не только спрос.
 */
public interface StandContent {

    /** Одна позиция сетки. */
    record Row(Material material, int amount, double pricePerStack) {
    }

    String title();

    List<Row> rows();

    /** Клик по строке списка. */
    void onRow(Player player, Row row);

    /** Кнопка «Меню» рядом со стендом. */
    void onMenu(Player player);
}
