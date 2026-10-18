package org.webtrade.webTrade.command;

import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Команда, которая просто открывает экран.
 * Раз вся логика теперь в интерфейсе, отдельные классы под каждую команду не нужны.
 */
public final class ScreenCommand implements CommandExecutor {

    private final Consumer<Player> screen;

    public ScreenCommand(Consumer<Player> screen) {
        this.screen = screen;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (!(sender instanceof Player player)) {
            sender.sendMessage("Только для игроков.");
            return true;
        }
        screen.accept(player);
        return true;
    }
}
