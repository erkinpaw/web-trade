package org.webtrade.webTrade.resource;

import org.bukkit.Material;

/** Торгуемый ресурс: материал и цена за стак (64 шт.). Цена за штуку = pricePerStack / 64. */
public record ResourceType(Material material, double pricePerStack) {

    public static final int STACK = 64;

    public double pricePerUnit() {
        return pricePerStack / STACK;
    }
}
