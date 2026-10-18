package org.webtrade.webTrade.market;

import org.bukkit.Material;

import java.util.UUID;

/**
 * Ресурсы, сданные добытчиком, но ещё не сведённые со спросом.
 * Лежат на общем складе и ждут, пока на них появится спрос — тогда добытчик получит оплату.
 */
public final class SupplyEntry {

    private final UUID seller;
    private final Material material;
    private final long createdAt;
    private int amount;

    public SupplyEntry(UUID seller, Material material, int amount, long createdAt) {
        this.seller = seller;
        this.material = material;
        this.amount = amount;
        this.createdAt = createdAt;
    }

    public UUID seller() {
        return seller;
    }

    public Material material() {
        return material;
    }

    public int amount() {
        return amount;
    }

    public long createdAt() {
        return createdAt;
    }

    /** Списывает часть запаса при сведении со спросом. */
    public void reduce(int taken) {
        this.amount = Math.max(0, this.amount - taken);
    }
}
