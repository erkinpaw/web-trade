package org.webtrade.webTrade.command;

import net.kyori.adventure.text.minimessage.MiniMessage;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;

/**
 * Открыть биржу командой «откуда угодно» можно только с правом {@value #PERMISSION}.
 * Без него биржа доступна на стендах торгового центра — им это право не нужно.
 * <p>
 * По умолчанию право есть у всех (plugin.yml), так что ничего не меняется, пока сервер
 * сам не решит его забрать — например, оставить только донатерам.
 */
public final class RemoteAccess implements CommandExecutor {

    public static final String PERMISSION = "webtrade.command";

    private final Plugin plugin;
    private final CommandExecutor inner;

    public RemoteAccess(Plugin plugin, CommandExecutor inner) {
        this.plugin = plugin;
        this.inner = inner;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        if (sender instanceof Player player && !player.hasPermission(PERMISSION)) {
            player.sendMessage(MiniMessage.miniMessage().deserialize(plugin.getConfig().getString(
                    "commands.no-remote-access",
                    "<gray>Биржа открывается на стендах торгового центра.")));
            return true;
        }
        return inner.onCommand(sender, command, label, args);
    }
}
