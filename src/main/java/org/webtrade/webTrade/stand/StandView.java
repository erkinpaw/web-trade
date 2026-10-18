package org.webtrade.webTrade.stand;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Location;
import org.bukkit.entity.Display;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Interaction;
import org.bukkit.entity.ItemDisplay;
import org.bukkit.entity.Player;
import org.bukkit.entity.TextDisplay;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.bukkit.util.Transformation;
import org.joml.AxisAngle4f;
import org.joml.Vector3f;
import org.webtrade.webTrade.ui.Ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Персональная копия стенда для одного игрока: вертикальный список ресурсов на подложке.
 * <p>
 * Сущности спавнятся с {@code setVisibleByDefault(false)} и показываются только своему
 * зрителю — поэтому двое могут стоять у одного стенда и листать разные места списка.
 * Прокрутка не пересоздаёт сущности, а меняет их содержимое, поэтому длина списка
 * за окном не стоит ничего.
 */
public final class StandView {

    /** Фона нет: надписи висят прямо в воздухе. */
    private static final Color TRANSPARENT = Color.fromARGB(0, 0, 0, 0);

    private static final float ICON_SCALE = 0.26f;
    private static final float TEXT_SCALE = 0.40f;
    private static final float CAPTION_SCALE = 0.35f;
    private static final float HEADER_SCALE = 0.70f;
    private static final float MENU_SCALE = 0.85f;
    private static final float ARROW_SCALE = 0.65f;
    /** Множитель дальности прорисовки: дальше клиент стенд не рисует. */
    private static final float VIEW_RANGE = 0.6f;
    /** Подложка тёмная, поэтому светим всё изнутри — иначе ночью стенд не прочитать. */
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);

    private final Plugin plugin;
    private final BoardLayout layout;
    private final Player viewer;
    private final StandContent content;

    private final List<Entity> spawned = new ArrayList<>();
    private final List<ItemDisplay> icons = new ArrayList<>();
    private final List<TextDisplay> names = new ArrayList<>();
    private final List<TextDisplay> demands = new ArrayList<>();
    private final List<TextDisplay> prices = new ArrayList<>();
    private final List<Interaction> hitboxes = new ArrayList<>();
    /** Что делать по клику: ключ — UUID Interaction-сущности. */
    private final Map<UUID, Consumer<Player>> actions = new HashMap<>();

    private TextDisplay header;
    private TextDisplay footer;

    private int offset;
    private int hovered = -1;
    private List<StandContent.Row> rows = List.of();

    public StandView(Plugin plugin, BoardLayout layout, Player viewer, StandContent content) {
        this.plugin = plugin;
        this.layout = layout;
        this.viewer = viewer;
        this.content = content;
    }

    // ---------- сборка ----------

    public void spawn() {
        header = text(layout.header(), HEADER_SCALE);
        caption(layout.captions(), -1.15, "ресурс");
        caption(layout.captions(), 0.75, "нужно");
        caption(layout.captions(), 1.95, "за стак");

        for (int row = 0; row < layout.rows(); row++) {
            int index = row;
            icons.add(icon(layout.icon(row)));
            names.add(text(layout.name(row), TEXT_SCALE));
            demands.add(text(layout.demand(row), TEXT_SCALE));
            prices.add(text(layout.price(row), TEXT_SCALE));
            hitboxes.add(hitbox(layout.rowHitbox(row), layout.width(), layout.hitboxHeight(),
                    player -> clickRow(player, index)));
        }

        footer = text(layout.footer(), CAPTION_SCALE);

        button(layout.menuButton(), "[ МЕНЮ ]", NamedTextColor.GREEN, MENU_SCALE,
                1.9, 0.55, content::onMenu);
        button(layout.scrollUp(), "▲", NamedTextColor.YELLOW, ARROW_SCALE,
                0.8, 0.45, player -> scroll(-1));
        button(layout.scrollDown(), "▼", NamedTextColor.YELLOW, ARROW_SCALE,
                0.8, 0.45, player -> scroll(1));

        refresh();
    }

    /** Пересобрать содержимое, не трогая сами сущности. */
    public void refresh() {
        rows = content.rows();
        offset = Math.clamp(offset, 0, Math.max(0, rows.size() - layout.rows()));

        for (int i = 0; i < layout.rows(); i++) {
            int index = offset + i;
            if (index < rows.size()) {
                StandContent.Row row = rows.get(index);
                icons.get(i).setItemStack(new ItemStack(row.material()));
                names.get(i).text(nameText(i, row));
                demands.get(i).text(Ui.text(amount(row.amount()), NamedTextColor.WHITE));
                prices.get(i).text(Ui.text(Ui.money(row.pricePerStack()) + " ар", NamedTextColor.GOLD));
            } else {
                icons.get(i).setItemStack(null);
                names.get(i).text(Component.empty());
                demands.get(i).text(Component.empty());
                prices.get(i).text(Component.empty());
            }
        }

        header.text(Ui.text(content.title(), NamedTextColor.GOLD));
        footer.text(rows.isEmpty()
                ? Ui.text("заказов сейчас нет", NamedTextColor.DARK_GRAY)
                : Ui.text((offset + 1) + "–" + Math.min(rows.size(), offset + layout.rows())
                        + " из " + rows.size() + "   ·   колесо мыши", NamedTextColor.GRAY));
    }

    /** Подсветка строки, на которую игрок сейчас смотрит. */
    public void hover(Entity target) {
        int index = target == null ? -1 : hitboxes.indexOf(target);
        if (index == hovered) {
            return;
        }
        int previous = hovered;
        hovered = index;
        repaint(previous);
        repaint(hovered);
    }

    private void repaint(int visibleRow) {
        if (visibleRow < 0 || visibleRow >= names.size()) {
            return;
        }
        int index = offset + visibleRow;
        if (index < rows.size()) {
            names.get(visibleRow).text(nameText(visibleRow, rows.get(index)));
        }
    }

    private Component nameText(int visibleRow, StandContent.Row row) {
        return Ui.name(row.material()).color(
                visibleRow == hovered ? NamedTextColor.YELLOW : NamedTextColor.WHITE);
    }

    // ---------- ввод ----------

    public void scroll(int deltaRows) {
        int max = Math.max(0, rows.size() - layout.rows());
        int updated = Math.clamp(offset + deltaRows, 0, max);
        if (updated != offset) {
            offset = updated;
            refresh();
        }
    }

    /** Клик по любой сущности стенда. true, если это была наша сущность. */
    public boolean click(Player player, Entity clicked) {
        Consumer<Player> action = actions.get(clicked.getUniqueId());
        if (action == null) {
            return false;
        }
        action.accept(player);
        return true;
    }

    public boolean owns(Entity entity) {
        return actions.containsKey(entity.getUniqueId());
    }

    private void clickRow(Player player, int visibleRow) {
        int index = offset + visibleRow;
        if (index < rows.size()) {
            content.onRow(player, rows.get(index));
        }
    }

    // ---------- уборка ----------

    public void despawn() {
        spawned.forEach(entity -> {
            if (entity.isValid()) {
                entity.remove();
            }
        });
        spawned.clear();
        icons.clear();
        names.clear();
        demands.clear();
        prices.clear();
        hitboxes.clear();
        actions.clear();
        header = null;
        footer = null;
    }

    // ---------- спавн отдельных кусочков ----------

    private ItemDisplay icon(Location where) {
        return spawn(where, ItemDisplay.class, display -> {
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            display.setBillboard(Display.Billboard.CENTER);
            display.setBrightness(FULL_BRIGHT);
            display.setTransformation(scaled(ICON_SCALE));
            display.setViewRange(VIEW_RANGE);
        });
    }

    private TextDisplay text(Location where, float scale) {
        return spawn(where, TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.CENTER);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setBackgroundColor(TRANSPARENT);
            display.setBrightness(FULL_BRIGHT);
            display.setTransformation(scaled(scale));
            display.setViewRange(VIEW_RANGE);
            display.setSeeThrough(false);
            display.text(Component.empty());
        });
    }

    private void caption(Location row, double sideOffset, String label) {
        Location where = row.clone().add(layout.rightward().multiply(sideOffset));
        text(where, CAPTION_SCALE).text(Ui.text(label, NamedTextColor.DARK_GRAY));
    }

    private void button(Location where, String label, NamedTextColor color, float scale,
                        double hitWidth, double hitHeight, Consumer<Player> action) {
        text(where, scale).text(Ui.text(label, color));
        hitbox(where, hitWidth, hitHeight, action);
    }

    private Interaction hitbox(Location center, double width, double height, Consumer<Player> action) {
        // Коробка Interaction растёт вверх от точки спавна — опускаем её на половину высоты.
        Location base = center.clone().add(0, -height / 2.0, 0);
        Interaction interaction = spawn(base, Interaction.class, entity -> {
            entity.setInteractionWidth((float) width);
            entity.setInteractionHeight((float) height);
            entity.setResponsive(true);
        });
        actions.put(interaction.getUniqueId(), action);
        return interaction;
    }

    private <T extends Entity> T spawn(Location where, Class<T> type, Consumer<T> setup) {
        T entity = where.getWorld().spawn(where, type, fresh -> {
            fresh.setPersistent(false);
            fresh.setVisibleByDefault(false);
            setup.accept(fresh);
        });
        // Показываем только своему зрителю — остальные этих сущностей не увидят вовсе.
        viewer.showEntity(plugin, entity);
        spawned.add(entity);
        return entity;
    }

    private static Transformation scaled(float scale) {
        return new Transformation(
                new Vector3f(0, 0, 0),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0, 0, 0, 1));
    }

    /** Тысячи разделяем пробелом — «1 487» читается быстрее, чем «1487». */
    private static String amount(int value) {
        return String.format("%,d", value).replace(',', ' ');
    }
}
