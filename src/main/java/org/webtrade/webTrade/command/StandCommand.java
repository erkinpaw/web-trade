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

/** /tradestand create|remove|list — расстановка стендов торгового центра. */
public final class StandCommand implements CommandExecutor {

    private final StandManager stands;

    public StandCommand(StandManager stands) {
        this.stands = stands;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        String action = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
        switch (action) {
            case "create" -> stands.create(player);
            case "remove" -> {
                if (stands.removeNearest(player)) {
                    player.sendMessage(Ui.text("Стенд убран.", NamedTextColor.GREEN));
                } else {
                    player.sendMessage(Ui.text("Рядом нет стенда.", NamedTextColor.RED));
                }
            }
            case "list" -> player.sendMessage(Ui.text("Стендов всего: " + stands.count(),
                    NamedTextColor.YELLOW));
            default -> player.sendMessage(Ui.text(
                    "Использование: /tradestand create | remove | list", NamedTextColor.YELLOW));
        }
        return true;
    }
}
