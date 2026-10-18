package org.webtrade.webTrade.command;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.webtrade.webTrade.currency.CurrencyService;
import org.webtrade.webTrade.ui.Screens;

/**
 * /withdraw [amount] — снять целое число руды со счёта в инвентарь.
 * Без аргумента открываем диалог со слайдером — там сразу видно, сколько доступно.
 */
public final class WithdrawCommand implements CommandExecutor {

    private final CurrencyService currency;
    private final Screens screens;

    public WithdrawCommand(CurrencyService currency, Screens screens) {
        this.currency = currency;
        this.screens = screens;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        if (args.length < 1) {
            screens.withdrawDialog(player);
            return true;
        }
        int amount;
        try {
            amount = Integer.parseInt(args[0]);
        } catch (NumberFormatException e) {
            player.sendMessage(Component.text("Укажите целое число.", NamedTextColor.RED));
            return true;
        }
        if (amount <= 0) {
            player.sendMessage(Component.text("Количество должно быть больше нуля.", NamedTextColor.RED));
            return true;
        }
        int delivered = currency.withdrawOre(player, amount);
        if (delivered < 0) {
            player.sendMessage(Component.text("Недостаточно средств на счёте.", NamedTextColor.RED));
        } else if (delivered < amount) {
            player.sendMessage(Component.text("Выдано " + delivered + " руды (инвентарь заполнен), остальное возвращено на счёт.",
                    NamedTextColor.YELLOW));
        } else {
            player.sendMessage(Component.text("Выдано " + delivered + " руды.", NamedTextColor.GREEN));
        }
        return true;
    }
}
