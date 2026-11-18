package org.webtrade.webTrade.command;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.webtrade.webTrade.stand.StandManager;
import org.webtrade.webTrade.ui.Ui;

import java.util.Locale;

/** /tradestand create|center|remove|list — расстановка стендов торгового центра. */
public final class StandCommand implements CommandExecutor {

    private final StandManager stands;

    public StandCommand(StandManager stands) {
        this.stands = stands;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        if (!(sender instanceof Player player)) {
            // из консоли (и по RCON) можно только выровнять сразу все стенды
            if (action.equals("center")) {
                stands.centerAll(sender::sendMessage);
                sender.sendMessage("Выравниваю стендов: " + stands.count());
            } else {
                sender.sendMessage("Из консоли: /tradestand center — выровнять все стенды.");
            }
            return true;
        }
        switch (action) {
            case "create" -> stands.create(player);
            case "remove" -> {
                if (stands.removeNearest(player)) {
                    player.sendMessage(Ui.text("Стенд убран.", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Ui.text("Рядом нет стенда.", NamedTextColor.RED));
                }
            }
            case "center" -> {
                if (!stands.centerNearest(player)) {
                    player.sendMessage(Ui.text("Рядом нет стенда.", NamedTextColor.RED));
                }
            }
            case "list" -> player.sendMessage(Ui.text("Стендов всего: " + stands.count(),
                    NamedTextColor.YELLOW));
            default -> player.sendMessage(Ui.text(
                    "Использование: /tradestand create | center | remove | list", NamedTextColor.YELLOW));
        }
        return true;
    }
}
