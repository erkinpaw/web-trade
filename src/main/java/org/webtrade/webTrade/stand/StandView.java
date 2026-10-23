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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
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

    /** У самих надписей фона нет — общий фон даёт подложка за всем стендом. */
    private static final Color TRANSPARENT = Color.fromARGB(0, 0, 0, 0);
    /** Подложка: тёмная и слегка прозрачная, чтобы стенд читался на любом фоне. */
    private static final Color PANEL_COLOR = Color.fromARGB(200, 14, 16, 22);

    /*
     * Размеры текста в блоках при масштабе 1. Клиент рисует пиксель шрифта в 1/40 блока,
     * строка — 9 пикселей плюс 1 на интервал, фон шире текста на пиксель.
     * Пробел шириной 4 пикселя даёт фон 5×10 пикселей — из него и тянем подложку.
     */
    private static final double PIXEL = 0.025;
    private static final double LINE_HEIGHT = 10 * PIXEL;
    private static final double SPACE_BACKGROUND_WIDTH = 5 * PIXEL;
    /** Фон пробела смещён вправо на полпикселя относительно точки сущности. */
    private static final double SPACE_BACKGROUND_SHIFT = 0.5 * PIXEL;

    private static final float ICON_SCALE = 0.26f;
    private static final float TEXT_SCALE = 0.42f;
    private static final float CAPTION_SCALE = 0.38f;
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
    /** Наши Interaction-сущности: по ним понимаем, что игрок целится в стенд. */
    private final Set<UUID> hitboxes = new HashSet<>();
    /**
     * Кликабельные области в плоскости стенда. Что именно под прицелом, решаем по ним,
     * а не по сущности, — см. {@link BoardLayout#gaze}.
     */
    private final List<Region> regions = new ArrayList<>();

    /** Прямоугольник на стенде: центр, полуразмеры и что делать по клику. row — строка списка или -1. */
    private record Region(double side, double heightRow, double halfWidth, double halfHeightRows,
                          int row, Consumer<Player> action) {

        boolean contains(BoardLayout.Gaze gaze) {
            return Math.abs(gaze.side() - side) <= halfWidth
                    && Math.abs(gaze.heightRow() - heightRow) <= halfHeightRows;
        }
    }

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
        panel();
        header = text(layout.header(), HEADER_SCALE);
        caption(layout.captions(), BoardLayout.NAME_X, "ресурс");
        caption(layout.captions(), BoardLayout.DEMAND_X, "нужно");
        caption(layout.captions(), BoardLayout.PRICE_X, "за стак");

        for (int row = 0; row < layout.rows(); row++) {
            int index = row;
            icons.add(icon(layout.icon(row)));
            names.add(text(layout.name(row), TEXT_SCALE));
            demands.add(text(layout.demand(row), TEXT_SCALE));
            prices.add(text(layout.price(row), TEXT_SCALE));
            hitbox(layout.rowHitbox(row), layout.width(), layout.hitboxHeight(), index,
                    player -> clickRow(player, index));
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
                ? Ui.text("заказов сейчас нет", NamedTextColor.GRAY)
                : Ui.text((offset + 1) + "–" + Math.min(rows.size(), offset + layout.rows())
                        + " из " + rows.size() + "   ·   колесо мыши", NamedTextColor.GRAY));
    }

    /** Подсветка строки, на которую игрок сейчас смотрит. */
    public void hover(Entity target) {
        Region region = target != null && owns(target) ? regionUnderGaze() : null;
        int index = region == null ? -1 : region.row();
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
        if (!owns(clicked)) {
            return false;
        }
        Region region = regionUnderGaze();
        if (region != null) {
            region.action().accept(player);
        }
        return true;
    }

    public boolean owns(Entity entity) {
        return hitboxes.contains(entity.getUniqueId());
    }

    private Region regionUnderGaze() {
        BoardLayout.Gaze gaze = layout.gaze(viewer.getEyeLocation());
        if (gaze == null) {
            return null;
        }
        for (Region region : regions) {
            if (region.contains(gaze)) {
                return region;
            }
        }
        return null;
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
        regions.clear();
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
            // Надписи развёрнуты жёстко, как и подложка: поворачиваясь за игроком,
            // длинное название сбоку наполовину уходило за неё.
            display.setBillboard(Display.Billboard.FIXED);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setBackgroundColor(TRANSPARENT);
            // Тень делает пиксельный шрифт заметно чётче на тёмной подложке.
            display.setShadowed(true);
            display.setBrightness(FULL_BRIGHT);
            // Клиент рисует текст вверх от точки сущности, а иконку — по центру.
            // Опускаем текст на полстроки, чтобы название стояло вровень с иконкой.
            display.setTransformation(new Transformation(
                    new Vector3f(0, (float) (-LINE_HEIGHT * scale / 2.0), 0),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(scale, scale, scale),
                    new AxisAngle4f(0, 0, 0, 1)));
            display.setViewRange(VIEW_RANGE);
            display.setSeeThrough(false);
            display.text(Component.empty());
        });
    }

    private void caption(Location row, double sideOffset, String label) {
        Location where = row.clone().add(layout.rightward().multiply(sideOffset));
        text(where, CAPTION_SCALE).text(Ui.text(label, NamedTextColor.GRAY));
    }

    private void button(Location where, String label, NamedTextColor color, float scale,
                        double hitWidth, double hitHeight, Consumer<Player> action) {
        text(where, scale).text(Ui.text(label, color));
        hitbox(where, hitWidth, hitHeight, -1, action);
    }

    /**
     * Сплошная подложка под всем стендом: один текстовый дисплей с пробелом,
     * фон которого растянут масштабом до размеров стенда. Развёрнут жёстко, лицом
     * к зрителям, — в отличие от надписей, которые поворачиваются за игроком.
     */
    private void panel() {
        double scaleX = layout.panelWidth() / SPACE_BACKGROUND_WIDTH;
        double scaleY = layout.panelHeight() / LINE_HEIGHT;
        spawn(layout.panelCenter(), TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.FIXED);
            display.text(Component.text(" "));
            display.setBackgroundColor(PANEL_COLOR);
            display.setBrightness(FULL_BRIGHT);
            display.setViewRange(VIEW_RANGE);
            // Фон растёт вверх от точки сущности — опускаем его на половину высоты.
            display.setTransformation(new Transformation(
                    new Vector3f((float) (-SPACE_BACKGROUND_SHIFT * scaleX),
                            (float) (-layout.panelHeight() / 2.0), 0),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f((float) scaleX, (float) scaleY, 1f),
                    new AxisAngle4f(0, 0, 0, 1)));
        });
    }

    private void hitbox(Location center, double width, double height, int row, Consumer<Player> action) {
        // Коробка Interaction растёт вверх от точки спавна — опускаем её на половину высоты.
        Location base = center.clone().add(0, -height / 2.0, 0);
        Interaction interaction = spawn(base, Interaction.class, entity -> {
            entity.setInteractionWidth((float) width);
            entity.setInteractionHeight((float) height);
            entity.setResponsive(true);
        });
        hitboxes.add(interaction.getUniqueId());
        BoardLayout.Gaze at = layout.project(center);
        regions.add(new Region(at.side(), at.heightRow(), width / 2.0,
                height / 2.0 / layout.rowHeight(), row, action));
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
