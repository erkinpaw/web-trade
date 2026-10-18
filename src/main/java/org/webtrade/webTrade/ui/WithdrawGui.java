package org.webtrade.webTrade.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;
import org.webtrade.webTrade.market.Container;

import java.util.List;
import java.util.Map;

/**
 * Выдача собранного: игрок забирает из контейнера прямо в инвентарь.
 * <p>
 * Все клики перехватывает {@link GuiListener}, а перекладыванием занимаемся мы сами.
 * Иначе шифт-клик, перетаскивание и цифровые клавиши дают дюп: путей слишком много,
 * чтобы закрыть каждый. Списываем строго после того, как предмет уже у игрока.
 */
public final class WithdrawGui extends PagedGui {

    private final Screens screens;
    private final Container container;

    public WithdrawGui(Screens screens, Container container) {
        this.screens = screens;
        this.container = container;
    }

    @Override
    protected Component title() {
        return Ui.text(container.label(), NamedTextColor.DARK_AQUA);
    }

    @Override
    protected List<Entry> entries() {
        List<Entry> entries = list();
        for (Map.Entry<Material, Container.Line> line : container.lines().entrySet()) {
            Material material = line.getKey();
            int ready = line.getValue().readyToWithdraw();
            int max = material.getMaxStackSize();
            // Раскладываем по стакам: один стак — одна ячейка, как в обычном сундуке.
            while (ready > 0) {
                int size = Math.min(ready, max);
                entries.add(new Entry(
                        Ui.icon(material, size, Ui.name(material), List.of(
                                Ui.text("Забрать: " + size + " шт.", NamedTextColor.WHITE),
                                Component.empty(),
                                Ui.text("ЛКМ — в инвентарь", NamedTextColor.YELLOW),
                                Ui.text("Ctrl+Q — выбросить под ноги", NamedTextColor.YELLOW))),
                        (player, click) -> take(player, material, size, click)));
                ready -= size;
            }
        }
        if (entries.isEmpty()) {
            entries.add(Entry.inert(Ui.icon(Material.BARRIER,
                    Ui.text("Забирать пока нечего", NamedTextColor.RED),
                    List.of(Ui.text("Ресурсы появятся, когда их сдадут добытчики.")))));
        }
        return entries;
    }

    private void take(Player player, Material material, int size, ClickType click) {
        boolean drop = click == ClickType.CONTROL_DROP || click == ClickType.DROP;

        int given;
        if (drop) {
            // Выбрасывание под ноги: место в инвентаре не нужно.
            given = size;
            player.getWorld().dropItemNaturally(player.getLocation(), new ItemStack(material, size));
        } else {
            ItemStack stack = new ItemStack(material, size);
            int leftover = player.getInventory().addItem(stack).values().stream()
                    .mapToInt(ItemStack::getAmount).sum();
            given = size - leftover;
            if (given <= 0) {
                player.sendMessage(Ui.text("В инвентаре нет места. Ctrl+Q выбросит под ноги.",
                        NamedTextColor.RED));
                return;
            }
        }

        // Списываем только после того, как предметы уже у игрока.
        screens.market().withdraw(container, material, given);
        refresh();
    }

    @Override
    protected Component backLabel() {
        return Ui.text("← К контейнерам", NamedTextColor.YELLOW);
    }

    @Override
    protected void onBack(Player player) {
        screens.containers(player);
    }
}
