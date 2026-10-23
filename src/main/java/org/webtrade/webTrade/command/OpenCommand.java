package org.webtrade.webTrade.command;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabExecutor;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;

/**
 * /tradeopen &lt;ник&gt; &lt;экран&gt; — открыть игроку экран биржи от имени администратора.
 * Работает и из консоли, и из командного блока: так меню можно повесить на NPC или кнопку,
 * а при проверке — показать нужный экран, не проходя к нему кликами.
 */
public final class OpenCommand implements TabExecutor {

    private final Map<String, Consumer<Player>> screens;

    public OpenCommand(Map<String, Consumer<Player>> screens) {
        this.screens = screens;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (args.length != 2) {
            sender.sendMessage("Использование: /" + label + " <ник> <" + String.join("|", screens.keySet()) + ">");
            return true;
        }
        Player player = Bukkit.getPlayerExact(args[0]);
        if (player == null) {
            sender.sendMessage("Игрок " + args[0] + " не в сети.");
            return true;
        }
        Consumer<Player> screen = screens.get(args[1].toLowerCase(Locale.ROOT));
        if (screen == null) {
            sender.sendMessage("Нет такого экрана. Есть: " + String.join(", ", screens.keySet()));
            return true;
        }
        screen.accept(player);
        return true;
    }

    @Override
    public List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command,
                                      @NotNull String label, @NotNull String[] args) {
        if (args.length == 1) {
            return null; // ники онлайн-игроков подставит сам сервер
        }
        if (args.length == 2) {
            String prefix = args[1].toLowerCase(Locale.ROOT);
            return screens.keySet().stream().filter(name -> name.startsWith(prefix)).toList();
        }
        return List.of();
    }
}
