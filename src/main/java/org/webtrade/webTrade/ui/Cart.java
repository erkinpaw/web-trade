package org.webtrade.webTrade.ui;

import org.bukkit.Material;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Черновик контейнера, который игрок набирает в каталоге до оплаты.
 * Живёт в памяти: не оплачен — значит, и терять нечего.
 */
public final class Cart {

    /** Больше одного двойного сундука на позицию за раз всё равно не выдать удобно. */
    public static final int MAX_PER_LINE = 100_000;

    private final Map<Material, Integer> lines = new LinkedHashMap<>();
    /** Название будущего контейнера: игрок задаёт его вместе со списком. */
    private String title = "";

    public String title() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title == null ? "" : title.trim();
    }

    /** Ставит количество для ресурса. Ноль и меньше — убрать позицию. */
    public void set(Material material, int amount) {
        if (amount <= 0) {
            lines.remove(material);
        } else {
            lines.put(material, Math.min(amount, MAX_PER_LINE));
        }
    }

    public int get(Material material) {
        return lines.getOrDefault(material, 0);
    }

    public void remove(Material material) {
        lines.remove(material);
    }

    public Map<Material, Integer> lines() {
        return Collections.unmodifiableMap(lines);
    }

    /** Копия для передачи в биржу — чтобы дальнейшие правки корзины на неё не влияли. */
    public Map<Material, Integer> snapshot() {
        return new LinkedHashMap<>(lines);
    }

    public boolean isEmpty() {
        return lines.isEmpty();
    }

    public int size() {
        return lines.size();
    }

    public void clear() {
        lines.clear();
        title = "";
    }
}
