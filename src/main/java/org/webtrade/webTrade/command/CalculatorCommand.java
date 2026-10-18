package org.webtrade.webTrade.command;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.NotNull;
import org.webtrade.webTrade.resource.CalculatorExport;
import org.webtrade.webTrade.resource.ResourceRegistry;
import org.webtrade.webTrade.schematic.LegacyBlocks;
import org.webtrade.webTrade.ui.Ui;

import java.io.IOException;
import java.nio.file.Path;

/** /tradecalc — выгрузить страницу калькулятора с текущими ценами. */
public final class CalculatorCommand implements CommandExecutor {

    private final Plugin plugin;
    private final ResourceRegistry resources;

    public CalculatorCommand(Plugin plugin, ResourceRegistry resources) {
        this.plugin = plugin;
        this.resources = resources;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command,
                             @NotNull String label, @NotNull String[] args) {
        try {
            // Таблицу старых блоков строим здесь: она нужна только для выгрузки,
            // а собирается через Bukkit и заметно дороже остального.
            LegacyBlocks legacy = new LegacyBlocks(plugin.getLogger());
            Path file = CalculatorExport.write(plugin, resources, legacy);
            sender.sendMessage(Ui.text("Калькулятор выгружен: " + file, NamedTextColor.GREEN));
            sender.sendMessage(Ui.text("Ресурсов в нём: " + resources.all().size()
                    + ". Файл самодостаточный — выложите куда угодно или откройте локально.",
                    NamedTextColor.GRAY));
        } catch (IOException e) {
            sender.sendMessage(Ui.text("Не вышло выгрузить: " + e.getMessage(), NamedTextColor.RED));
        }
        return true;
    }
}
