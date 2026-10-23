package org.webtrade.webTrade.stand;

import org.bukkit.Location;
import org.bukkit.util.Vector;

/**
 * Геометрия стенда: вертикальный список, растущий вверх от якоря.
 * <p>
 * Якорь — нижняя строка списка, примерно на уровне глаз подошедшего игрока. Список уходит
 * вверх (туда смотреть удобно), а кнопка «Меню» висит ниже якоря — под рукой у того,
 * кто подошёл вплотную.
 * <p>
 * Нормаль смотрит в сторону покупателей, поэтому колонки откладываются вдоль
 * {@code нормаль × вверх}: для стоящего перед стендом это привычное «слева направо».
 */
public record BoardLayout(Location anchor, int rows, double rowHeight, double width) {

    public static final int DEFAULT_ROWS = 10;
    public static final double DEFAULT_ROW_HEIGHT = 0.27;
    public static final double DEFAULT_WIDTH = 5.0;

    /**
     * Колонки, в блоках от середины строки: центр значка, левый край названий, правые края
     * спроса и цены — названия прижаты влево, числа вправо, как в обычной таблице.
     */
    static final double ICON_X = -2.15;
    static final double NAME_LEFT = -1.85;
    static final double DEMAND_RIGHT = 1.15;
    static final double PRICE_RIGHT = 2.45;

    /** Ряды служебных подписей, в единицах высоты строки относительно нижней строки. */
    private static final double CAPTIONS_ROW = 1.0;
    private static final double HEADER_ROW = 1.9;
    private static final double FOOTER_ROW = -0.85;
    private static final double MENU_ROW = -1.75;
    /** Полоса строки начинается чуть ниже её текста: текст висит от точки вверх. */
    private static final double ROW_STRIP_FROM = -0.55;
    private static final double PANEL_BOTTOM = -2.6;
    private static final double PANEL_TOP_MARGIN = 1.6;

    public static BoardLayout of(Location anchor, int rows) {
        return new BoardLayout(anchor.clone(), rows, DEFAULT_ROW_HEIGHT, DEFAULT_WIDTH);
    }

    /** Направление «от стенда к зрителю». */
    public Vector normal() {
        return new Vector(-Math.sin(yawRadians()), 0, Math.cos(yawRadians())).normalize();
    }

    /**
     * Вдоль строки — слева направо с точки зрения того, кто смотрит на стенд.
     * <p>
     * Зритель смотрит навстречу нормали, поэтому его «вправо» — это {@code вверх × нормаль}.
     * Обратный порядок множителей даёт зеркало, и весь список разворачивается справа налево.
     */
    public Vector rightward() {
        return new Vector(0, 1, 0).crossProduct(normal()).normalize();
    }

    public double yawRadians() {
        return Math.toRadians(anchor.getYaw());
    }

    /**
     * Точка в системе координат стенда.
     *
     * @param sideOffset сдвиг вбок в блоках
     * @param heightRow  высота в единицах строки, вверх от якоря
     */
    public Location at(double sideOffset, double heightRow) {
        return anchor.clone()
                .add(rightward().multiply(sideOffset))
                .add(new Vector(0, heightRow * rowHeight, 0));
    }

    /** Высота видимой строки: нулевая — верхняя, последняя лежит на якоре. */
    private double rowLevel(int visibleRow) {
        return rows - 1 - visibleRow;
    }

    public Location icon(int visibleRow) {
        return at(ICON_X, rowLevel(visibleRow));
    }

    public Location name(int visibleRow) {
        return at(NAME_LEFT, rowLevel(visibleRow));
    }

    public Location demand(int visibleRow) {
        return at(DEMAND_RIGHT, rowLevel(visibleRow));
    }

    public Location price(int visibleRow) {
        return at(PRICE_RIGHT, rowLevel(visibleRow));
    }


    public Location captions() {
        return at(0, rows - 1 + CAPTIONS_ROW);
    }

    public Location header() {
        return at(0, rows - 1 + HEADER_ROW);
    }

    public Location footer() {
        return at(0, FOOTER_ROW);
    }

    public Location menuButton() {
        return at(0, MENU_ROW);
    }


    /** Низ полосы строки (полоса подсветки висит отсюда вверх на высоту строки). */
    public Location row(int visibleRow) {
        return at(0, rowLevel(visibleRow) + ROW_STRIP_FROM);
    }

    /** Низ подложки — под кнопкой «Меню»; сама подложка тянется до шапки. */
    public Location panel() {
        return at(0, PANEL_BOTTOM);
    }

    public double panelWidth() {
        return width + 1.3;
    }

    public double panelHeight() {
        return (rows - 1 + HEADER_ROW + PANEL_TOP_MARGIN - PANEL_BOTTOM) * rowHeight;
    }

    /**
     * Куда на доске смотрит игрок: точка пересечения взгляда с плоскостью доски.
     *
     * @return {сдвиг вбок в блоках, высота в строках от якоря} или null, если мимо доски
     */
    public double[] look(Location eye, Vector direction, double maxDistance) {
        if (eye.getWorld() == null || !eye.getWorld().equals(anchor.getWorld())) {
            return null;
        }
        Vector n = normal();
        double facing = direction.dot(n);
        if (facing > -1e-3) {
            return null; // смотрит вдоль доски или ей в спину
        }
        Vector toPlane = anchor.toVector().subtract(eye.toVector());
        double distance = toPlane.dot(n) / facing;
        if (distance < 0 || distance > maxDistance) {
            return null;
        }
        Vector hit = eye.toVector().add(direction.clone().multiply(distance)).subtract(anchor.toVector());
        double side = hit.dot(rightward());
        double level = hit.getY() / rowHeight;
        boolean inside = Math.abs(side) <= panelWidth() / 2
                && level >= PANEL_BOTTOM && level <= PANEL_BOTTOM + panelHeight() / rowHeight;
        return inside ? new double[]{side, level} : null;
    }

    /** Строка списка под этой высотой (в строках от якоря), или -1. */
    public int rowAt(double level) {
        int visibleRow = rows - 1 - (int) Math.floor(level - ROW_STRIP_FROM);
        return visibleRow >= 0 && visibleRow < rows ? visibleRow : -1;
    }

    /** Игрок считается «у стенда», пока он в этом радиусе и в том же мире. */
    public boolean inRange(Location where, double radius) {
        return where.getWorld() != null
                && where.getWorld().equals(anchor.getWorld())
                && where.distanceSquared(anchor) <= radius * radius;
    }
}
