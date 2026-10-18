package org.webtrade.webTrade.currency;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.webtrade.webTrade.account.AccountManager;

import java.util.Locale;

/** Валюта «ары» = предмет алмазная глубиносланцевая руда. Депозит/вывод. */
public final class CurrencyService {

    public static final Material CURRENCY = Material.DEEPSLATE_DIAMOND_ORE;

    private final AccountManager accounts;
    private final double orePerAre;

    public CurrencyService(AccountManager accounts, double orePerAre) {
        this.accounts = accounts;
        this.orePerAre = orePerAre;
    }

    public double orePerAre() {
        return orePerAre;
    }

    /** Зачисляет на счёт стоимость oreCount единиц руды и сразу сохраняет счета. */
    public void depositOre(Player player, int oreCount) {
        accounts.deposit(player.getUniqueId(), player.getName(), oreCount * orePerAre);
        accounts.save();
    }

    /**
     * Выдаёт oreCount единиц руды со счёта.
     * @return фактически выданное количество, либо -1 если не хватило средств.
     */
    public int withdrawOre(Player player, int oreCount) {
        double cost = oreCount * orePerAre;
        if (!accounts.withdraw(player.getUniqueId(), cost)) {
            return -1;
        }
        var leftover = player.getInventory().addItem(new ItemStack(CURRENCY, oreCount));
        int notGiven = leftover.values().stream().mapToInt(ItemStack::getAmount).sum();
        if (notGiven > 0) {
            // не влезло в инвентарь — возвращаем на счёт
            accounts.deposit(player.getUniqueId(), player.getName(), notGiven * orePerAre);
        }
        accounts.save();
        return oreCount - notGiven;
    }

    /**
     * Разбирает содержимое окна депозита: руду зачисляет на счёт, остальное возвращает игроку.
     * Вызывается при закрытии окна и при выключении сервера, чтобы ничего не пропало.
     *
     * @return сколько ар зачислено
     */
    public double settleDeposit(Player player, Inventory inventory) {
        int ore = 0;
        int limit = Math.min(DepositGui.FREE_SLOTS, inventory.getSize());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack == null) {
                continue;
            }
            if (stack.getType() == CURRENCY) {
                ore += stack.getAmount();
            } else {
                giveBack(player, stack);
            }
            inventory.setItem(slot, null);
        }

        if (ore <= 0) {
            return 0.0;
        }
        depositOre(player, ore);
        double ares = ore * orePerAre;
        player.sendMessage(Component.text(
                "Зачислено " + String.format(Locale.US, "%.2f", ares) + " ар.",
                NamedTextColor.GREEN));
        return ares;
    }

    /** Отмена: возвращаем всё вложенное и ничего не зачисляем. */
    public void refundDeposit(Player player, Inventory inventory) {
        int limit = Math.min(DepositGui.FREE_SLOTS, inventory.getSize());
        for (int slot = 0; slot < limit; slot++) {
            ItemStack stack = inventory.getItem(slot);
            if (stack != null) {
                giveBack(player, stack);
                inventory.setItem(slot, null);
            }
        }
    }

    /** Вернуть предмет игроку; что не влезло — под ноги, чтобы не пропало. */
    private void giveBack(Player player, ItemStack stack) {
        player.getInventory().addItem(stack).values()
                .forEach(left -> player.getWorld().dropItemNaturally(player.getLocation(), left));
    }
}
