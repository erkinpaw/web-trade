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

    public static final int DEFAULT_ROWS = 15;
    public static final double DEFAULT_ROW_HEIGHT = 0.30;
    public static final double DEFAULT_WIDTH = 5.0;

    /** Смещения колонок от середины строки, в блоках: иконка, название, спрос, цена. */
    /*
     * Название центрируется по своей точке (выровнять влево нельзя: ширину русского
     * названия знает только клиент), поэтому колонка стоит посередине между иконкой
     * и числами — так даже самое длинное название, около двух блоков, не задевает соседей.
     */
    public static final double ICON_X = -2.15;
    public static final double NAME_X = -0.85;
    public static final double DEMAND_X = 0.85;
    public static final double PRICE_X = 1.95;

    /** Ряды служебных подписей, в единицах высоты строки относительно нижней строки. */
    private static final double CAPTIONS_ROW = 1.0;
    private static final double HEADER_ROW = 1.9;
    private static final double FOOTER_ROW = -0.85;
    private static final double MENU_ROW = -1.75;
    private static final double ARROW_OFFSET = 1.5;

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
        return at(NAME_X, rowLevel(visibleRow));
    }

    public Location demand(int visibleRow) {
        return at(DEMAND_X, rowLevel(visibleRow));
    }

    public Location price(int visibleRow) {
        return at(PRICE_X, rowLevel(visibleRow));
    }

    /** Кликабельная полоса на всю ширину строки. */
    public Location rowHitbox(int visibleRow) {
        return at(0, rowLevel(visibleRow));
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

    public Location scrollUp() {
        return at(-ARROW_OFFSET, MENU_ROW);
    }

    public Location scrollDown() {
        return at(ARROW_OFFSET, MENU_ROW);
    }

    /** Поля подложки вокруг содержимого, в блоках. */
    private static final double PANEL_MARGIN_SIDE = 0.25;
    private static final double PANEL_MARGIN_TOP = 0.35;
    private static final double PANEL_MARGIN_BOTTOM = 0.3;
    /** Подложку чуть отодвигаем за надписи, иначе они мерцают, сливаясь с ней. */
    private static final double PANEL_DEPTH = 0.12;

    /** Нижний край подложки в строках от якоря — под кнопкой «Меню». */
    private double panelBottomRow() {
        return MENU_ROW - PANEL_MARGIN_BOTTOM / rowHeight;
    }

    /** Верхний край — над заголовком. */
    private double panelTopRow() {
        return rows - 1 + HEADER_ROW + PANEL_MARGIN_TOP / rowHeight;
    }

    public double panelWidth() {
        return width + PANEL_MARGIN_SIDE * 2;
    }

    public double panelHeight() {
        return (panelTopRow() - panelBottomRow()) * rowHeight;
    }

    /** Середина подложки, лицом к зрителю — так же, как развёрнут сам стенд. */
    public Location panelCenter() {
        Location center = at(0, (panelTopRow() + panelBottomRow()) / 2.0)
                .subtract(normal().multiply(PANEL_DEPTH));
        center.setYaw(anchor.getYaw());
        center.setPitch(0f);
        return center;
    }

    /** Точка на плоскости стенда: сдвиг вбок в блоках и высота в строках от якоря. */
    public record Gaze(double side, double heightRow) {
    }

    /**
     * Куда на плоскости стенда приходится взгляд, или null, если игрок смотрит мимо неё.
     * <p>
     * По сущности, в которую упёрся взгляд, строку определять нельзя: коробка
     * {@code Interaction} не поворачивается и всегда квадратная в плане, так что полоса
     * шириной во весь стенд торчит на половину ширины к зрителю. Взгляд снизу вверх
     * упирается в её переднюю грань и попадает на несколько строк ниже, чем видно глазу.
     */
    public Gaze gaze(Location eye) {
        Vector direction = eye.getDirection();
        Vector normal = normal();
        double facing = direction.dot(normal);
        // Смотрит вдоль плоскости или на обратную сторону — попадания нет.
        if (facing > -1e-6) {
            return null;
        }
        double distance = anchor.toVector().subtract(eye.toVector()).dot(normal) / facing;
        if (distance < 0) {
            return null;
        }
        Vector relative = eye.toVector().add(direction.multiply(distance)).subtract(anchor.toVector());
        return new Gaze(relative.dot(rightward()), relative.getY() / rowHeight);
    }

    /** Та же система координат для готовой точки — чтобы описать в ней кнопку. */
    public Gaze project(Location point) {
        Vector relative = point.toVector().subtract(anchor.toVector());
        return new Gaze(relative.dot(rightward()), relative.getY() / rowHeight);
    }

    public double hitboxHeight() {
        return rowHeight * 0.9;
    }

    /** Игрок считается «у стенда», пока он в этом радиусе и в том же мире. */
    public boolean inRange(Location where, double radius) {
        return where.getWorld() != null
                && where.getWorld().equals(anchor.getWorld())
                && where.distanceSquared(anchor) <= radius * radius;
    }
}
