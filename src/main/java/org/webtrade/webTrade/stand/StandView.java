package org.webtrade.webTrade.stand;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Color;
import org.bukkit.Material;
import org.bukkit.block.data.BlockData;
import org.bukkit.Location;
import org.bukkit.entity.BlockDisplay;
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
import org.webtrade.webTrade.assets.CalculatorAssets;
import org.webtrade.webTrade.ui.Ui;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Персональная копия стенда для одного игрока: плоская доска с тёмной подложкой.
 * <p>
 * Все надписи развёрнуты в одну сторону (как табличка), а не каждая к камере: так доска
 * читается как одно целое. Значки — сами предметы в «инвентарном» ракурсе (ступеньки выглядят
 * ступеньками), сплющенные в картинку: с какой стороны ни смотри, это значок, а не кубик.
 * <p>
 * Сущности спавнятся с {@code setVisibleByDefault(false)} и показываются только своему
 * зрителю — двое могут стоять у одного стенда и листать разные места списка.
 * Прокрутка не пересоздаёт сущности, а меняет их текст.
 * <p>
 * Нажимается только кнопка «Меню». Строки — витрина: на клик не отвечают, иначе каждое
 * случайное нажатие ПКМ по доске писало бы в чат.
 */
public final class StandView {

    private static final Color TRANSPARENT = Color.fromARGB(0, 0, 0, 0);
    /**
     * Подложка (чёрный бетон) и подсветка (серый бетон: у стекла видны прожилки текстуры) — тонкие пластины (block display). У них точная
     * геометрия: фон текста растягивался бы со шрифтовыми отступами и съезжал вбок.
     */
    private static final BlockData PANEL = Material.BLACK_CONCRETE.createBlockData();
    private static final BlockData HOVER = Material.GRAY_CONCRETE.createBlockData();
    private static final BlockData NONE = Material.AIR.createBlockData();

    private static final float ICON_SCALE = 0.26f;
    /** Толщина значка: почти ноль — модель предмета становится плоской картинкой. */
    private static final float ICON_DEPTH = 0.001f;
    private static final float TEXT_SCALE = 0.50f;
    private static final float CAPTION_SCALE = 0.40f;
    private static final float HEADER_SCALE = 0.80f;
    private static final float MENU_SCALE = 0.85f;
    /** Множитель дальности прорисовки: дальше клиент стенд не рисует. */
    private static final float VIEW_RANGE = 0.6f;
    /** Подложка тёмная, поэтому светим всё изнутри — иначе ночью стенд не прочитать. */
    private static final Display.Brightness FULL_BRIGHT = new Display.Brightness(15, 15);

    private final Plugin plugin;
    private final BoardLayout layout;
    private final Player viewer;
    private final StandContent content;
    /** Русские названия и ширина букв шрифта игры — по ним колонки выравниваются по краю. */
    private final Supplier<CalculatorAssets.Result> assets;

    private final List<Entity> spawned = new ArrayList<>();
    private final List<ItemDisplay> icons = new ArrayList<>();
    /** Полоса под каждой строкой: подсветка просто включается, а не ездит — так она не мигает. */
    private final List<BlockDisplay> strips = new ArrayList<>();
    private final List<TextDisplay> names = new ArrayList<>();
    private final List<TextDisplay> demands = new ArrayList<>();
    private final List<TextDisplay> prices = new ArrayList<>();

    private TextDisplay header;
    private TextDisplay footer;
    private Interaction menu;

    private int offset;
    private int hovered = -1;
    private List<StandContent.Row> rows = List.of();

    public StandView(Plugin plugin, BoardLayout layout, Player viewer, StandContent content,
                     Supplier<CalculatorAssets.Result> assets) {
        this.plugin = plugin;
        this.layout = layout;
        this.viewer = viewer;
        this.content = content;
        this.assets = assets;
    }

    // ---------- сборка ----------

    public void spawn() {
        panel(layout.panel(), layout.panelWidth(), layout.panelHeight(), PANEL, 0.06f);

        header = text(layout.header(), HEADER_SCALE);
        caption(layout.captions(), BoardLayout.NAME_LEFT, "ресурс", LEFT);
        caption(layout.captions(), BoardLayout.DEMAND_RIGHT, "нужно", RIGHT);
        caption(layout.captions(), BoardLayout.PRICE_RIGHT, "за стак", RIGHT);

        for (int row = 0; row < layout.rows(); row++) {
            // полосы чуть ближе подложки, иначе они мерцают, деля с ней одну глубину
            strips.add(panel(layout.row(row), layout.panelWidth() - 0.2, layout.rowHeight(), NONE, 0.03f));
            icons.add(icon(layout.icon(row)));
            names.add(text(layout.name(row), TEXT_SCALE));
            demands.add(text(layout.demand(row), TEXT_SCALE));
            prices.add(text(layout.price(row), TEXT_SCALE));
        }

        footer = text(layout.footer(), CAPTION_SCALE);
        text(layout.menuButton(), MENU_SCALE).text(Ui.text("[ МЕНЮ ]", NamedTextColor.GREEN));
        menu = hitbox(layout.menuButton(), 1.9, 0.55);

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
                put(names.get(i), TEXT_SCALE, LEFT, nameOf(row), nameColor(i));
                put(demands.get(i), TEXT_SCALE, RIGHT, amount(row.amount()), NamedTextColor.WHITE);
                put(prices.get(i), TEXT_SCALE, RIGHT, Ui.money(row.pricePerStack()) + " ар", NamedTextColor.GOLD);
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
        paintHover();
    }

    /** Подсветка строки, на которую игрок смотрит; -1 — ни на какую. */
    public void hover(int visibleRow) {
        int index = visibleRow >= 0 && offset + visibleRow < rows.size() ? visibleRow : -1;
        if (index == hovered) {
            return;
        }
        int previous = hovered;
        hovered = index;
        repaint(previous);
        repaint(hovered);
        paintHover();
    }

    private void paintHover() {
        for (int i = 0; i < strips.size(); i++) {
            BlockData wanted = i == hovered ? HOVER : NONE;
            // шлём изменение только той полосе, у которой оно правда есть
            if (!strips.get(i).getBlock().equals(wanted)) {
                strips.get(i).setBlock(wanted);
            }
        }
    }

    private void repaint(int visibleRow) {
        if (visibleRow < 0 || visibleRow >= names.size()) {
            return;
        }
        int index = offset + visibleRow;
        if (index < rows.size()) {
            put(names.get(visibleRow), TEXT_SCALE, LEFT, nameOf(rows.get(index)), nameColor(visibleRow));
        }
    }

    private NamedTextColor nameColor(int visibleRow) {
        return visibleRow == hovered ? NamedTextColor.YELLOW : NamedTextColor.WHITE;
    }

    /** Русское название — его же и меряем. Без ассетов — техническое имя: его ширина тоже известна. */
    private String nameOf(StandContent.Row row) {
        String russian = assets.get().names().get(row.material().name());
        return russian != null ? russian
                : row.material().name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
    }

    // ---------- выравнивание колонок ----------

    private static final int LEFT = -1;
    private static final int RIGHT = 1;
    /** Пиксель шрифта на текстовой надписи — 1/40 блока при масштабе 1. */
    private static final float PIXEL = 1 / 40f;
    /** Середина букв от низа надписи, в пикселях шрифта: строка 9 px, буквы на 1..8. */
    private static final float GLYPH_MIDDLE = 4.5f;

    /**
     * Текст, прижатый к краю колонки. Игра умеет только центрировать надпись на её точке,
     * поэтому сдвигаем её на полширины, и точка надписи становится краем колонки.
     * Ширину считаем по шрифту игры; без него остаётся центр.
     */
    private void put(TextDisplay display, float scale, int edge, String text, NamedTextColor color) {
        display.text(Ui.text(text, color));
        int width = width(text);
        float shift = width < 0 ? 0 : -edge * width * PIXEL * scale / 2f;
        display.setTransformation(new Transformation(
                // надпись висит от точки вверх — опускаем на полвысоты строки шрифта, чтобы её
                // середина легла ровно на линию строки (там же центр значка и полосы подсветки)
                new Vector3f(shift, -GLYPH_MIDDLE * PIXEL * scale, 0),
                new AxisAngle4f(0, 0, 0, 1),
                new Vector3f(scale, scale, scale),
                new AxisAngle4f(0, 0, 0, 1)));
    }

    /** Ширина строки в пикселях шрифта игры; -1 — шрифт неизвестен. */
    private int width(String text) {
        var glyphs = assets.get().glyphs();
        if (glyphs.isEmpty()) {
            return -1;
        }
        int width = 0;
        for (int i = 0; i < text.length(); ) {
            int cp = text.codePointAt(i);
            width += glyphs.getOrDefault(cp, 6);
            i += Character.charCount(cp);
        }
        // у последней буквы нет промежутка после неё
        return Math.max(0, width - 1);
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

    /** Кликнули по сущности стенда. true — это была кнопка «Меню». */
    public boolean click(Player player, Entity clicked) {
        if (menu == null || !clicked.getUniqueId().equals(menu.getUniqueId())) {
            return false;
        }
        content.onMenu(player);
        return true;
    }

    public BoardLayout layout() {
        return layout;
    }

    public boolean owns(Entity entity) {
        return menu != null && menu.getUniqueId().equals(entity.getUniqueId());
    }

    // ---------- уборка ----------

    public void despawn() {
        spawned.forEach(entity -> {
            if (!entity.isValid()) {
                return;
            }
            // Folia: only the entity's own region may remove it (a player who left — from another thread)
            if (org.bukkit.Bukkit.isOwnedByCurrentRegion(entity)) {
                entity.remove();
            } else {
                entity.getScheduler().run(plugin, t -> entity.remove(), null);
            }
        });
        spawned.clear();
        icons.clear();
        strips.clear();
        names.clear();
        demands.clear();
        prices.clear();
        header = null;
        footer = null;
        menu = null;
    }

    // ---------- спавн отдельных кусочков ----------

    /** Значок предмета: инвентарный ракурс, развёрнут вместе с доской и сплющен в картинку. */
    private ItemDisplay icon(Location where) {
        return spawn(where, ItemDisplay.class, display -> {
            display.setItemDisplayTransform(ItemDisplay.ItemDisplayTransform.GUI);
            display.setBillboard(Display.Billboard.FIXED);
            display.setBrightness(FULL_BRIGHT);
            display.setViewRange(VIEW_RANGE);
            display.setTransformation(new Transformation(
                    new Vector3f(0, 0, 0),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f(ICON_SCALE, ICON_SCALE, ICON_DEPTH),
                    new AxisAngle4f(0, 0, 0, 1)));
        });
    }

    private TextDisplay text(Location where, float scale) {
        return spawn(where, TextDisplay.class, display -> {
            display.setBillboard(Display.Billboard.FIXED);
            display.setAlignment(TextDisplay.TextAlignment.CENTER);
            display.setBackgroundColor(TRANSPARENT);
            display.setBrightness(FULL_BRIGHT);
            display.setTransformation(scaled(scale));
            display.setViewRange(VIEW_RANGE);
            display.setSeeThrough(false);
            display.setShadowed(true);
            display.text(Component.empty());
        });
    }

    /**
     * Пластина фона: блок, растянутый до width×height и сплющенный до тонкого листа.
     * Блок рисуется от своего угла, поэтому сдвигаем его на половину ширины влево и чуть
     * назад — за текст.
     */
    private BlockDisplay panel(Location bottomCenter, double width, double height, BlockData block, float behind) {
        return spawn(bottomCenter, BlockDisplay.class, display -> {
            display.setBlock(block);
            display.setBrightness(FULL_BRIGHT);
            display.setViewRange(VIEW_RANGE);
            display.setTransformation(new Transformation(
                    new Vector3f((float) (-width / 2), 0, -behind),
                    new AxisAngle4f(0, 0, 0, 1),
                    new Vector3f((float) width, (float) height, 0.01f),
                    new AxisAngle4f(0, 0, 0, 1)));
        });
    }

    private void caption(Location row, double sideOffset, String label, int edge) {
        Location where = row.clone().add(layout.rightward().multiply(sideOffset));
        put(text(where, CAPTION_SCALE), CAPTION_SCALE, edge, label, NamedTextColor.GRAY);
    }

    private Interaction hitbox(Location center, double width, double height) {
        // Коробка Interaction растёт вверх от точки спавна — опускаем её на половину высоты.
        Location base = center.clone().add(0, -height / 2.0, 0);
        return spawn(base, Interaction.class, entity -> {
            entity.setInteractionWidth((float) width);
            entity.setInteractionHeight((float) height);
            entity.setResponsive(true);
        });
    }

    private <T extends Entity> T spawn(Location where, Class<T> type, Consumer<T> setup) {
        Location facing = where.clone();
        // Плоская доска смотрит на тех, кто стоит перед стендом.
        facing.setYaw(layout.anchor().getYaw());
        facing.setPitch(0f);
        T entity = facing.getWorld().spawn(facing, type, fresh -> {
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
