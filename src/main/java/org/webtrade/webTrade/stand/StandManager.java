package org.webtrade.webTrade.stand;

import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Entity;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageByEntityEvent;
import org.bukkit.event.player.PlayerItemHeldEvent;
import org.bukkit.event.player.PlayerInteractEntityEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.plugin.Plugin;
import org.webtrade.webTrade.ui.Ui;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

/**
 * Стенды торгового центра: где стоят, кому сейчас показаны и как реагируют.
 * <p>
 * Стенд не существует «вообще» — он существует для конкретного игрока, который к нему
 * подошёл. Подошёл — сущности заспавнились и показались только ему; отошёл — удалились.
 */
public final class StandManager implements Listener {

    /** Радиус появления и радиус исчезновения разные, иначе стенд мигает на границе. */
    private static final double SHOW_RADIUS = 26.0;
    private static final double HIDE_RADIUS = 32.0;
    /** Другой стенд должен быть ближе хотя бы на столько, чтобы показ переключился на него. */
    private static final double SWITCH_MARGIN = 4.0;
    /** Как далеко ищем сущность, на которую смотрит игрок. */
    private static final int LOOK_DISTANCE = 8;
    /** Подсветку строки под взглядом обновляем часто, иначе она отстаёт от мыши. */
    private static final int TASK_PERIOD = 4;
    private static final long CLICK_COOLDOWN_MS = 500;
    /** Через сколько тишины после последнего щелчка колеса хотбар возвращается на место. */
    private static final long SCROLL_SETTLE_MS = 400;

    private static final int DEFAULT_ROWS = BoardLayout.DEFAULT_ROWS;

    private final Plugin plugin;
    private final StandContent content;
    private final File file;

    // Folia: every player's view is ticked by that player's region, so these are touched from many threads
    private final List<BoardLayout> stands = new java.util.concurrent.CopyOnWriteArrayList<>();
    private final Map<UUID, StandView> views = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> lastClick = new java.util.concurrent.ConcurrentHashMap<>();
    /** Серия прокрутки колесом: слот, с которого игрок начал, и время последнего щелчка. */
    private final Map<UUID, Integer> scrollHome = new java.util.concurrent.ConcurrentHashMap<>();
    private final Map<UUID, Long> lastScroll = new java.util.concurrent.ConcurrentHashMap<>();
    /** В какую сторону игрок крутит в этой серии: +1 вниз по списку, -1 вверх. */
    private final Map<UUID, Integer> scrollDirection = new java.util.concurrent.ConcurrentHashMap<>();
    /** Кто сейчас тянет бегунок: за ним следим каждый тик, а не раз в {@link #TASK_PERIOD}. */
    private final Map<UUID, io.papermc.paper.threadedregions.scheduler.ScheduledTask> drags =
            new java.util.concurrent.ConcurrentHashMap<>();

    private io.papermc.paper.threadedregions.scheduler.ScheduledTask task;
    /** Счётчик тиков: цифры на стенде обновляем реже, чем следим за подходом игроков. */
    private int ticks;

    public StandManager(Plugin plugin, StandContent content) {
        this.plugin = plugin;
        this.content = content;
        this.file = new File(plugin.getDataFolder(), "stands.yml");
        load();
    }

    // ---------- жизненный цикл ----------

    public void start() {
        task = plugin.getServer().getGlobalRegionScheduler().runAtFixedRate(plugin, t -> tick(),
                TASK_PERIOD, TASK_PERIOD);
    }

    public void stop() {
        if (task != null) {
            task.cancel();
            task = null;
        }
        // Stand entities are not persistent: on shutdown they vanish with the world and must not be
        // touched — Folia's shutdown thread has no region data, and removing them there throws.
        if (!plugin.getServer().isStopping()) {
            views.values().forEach(StandView::despawn);
        }
        views.clear();
    }

    /** Раз в полсекунды решаем, кому стенд показать, кому убрать и что подсветить. */
    private void tick() {
        boolean refresh = ++ticks % (40 / TASK_PERIOD) == 0;
        // the stand and its entities are next to the player: the player's region owns them all
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            player.getScheduler().run(plugin, t -> tick(player, refresh), null);
        }
        // Игроки, которые вышли между тиками, могли оставить за собой сущности.
        views.entrySet().removeIf(entry -> {
            Player player = plugin.getServer().getPlayer(entry.getKey());
            if (player == null || !player.isOnline()) {
                entry.getValue().despawn();
                return true;
            }
            return false;
        });
    }

    private void tick(Player player, boolean refresh) {
        {
            StandView view = views.get(player.getUniqueId());
            BoardLayout nearby = nearest(player.getLocation(), SHOW_RADIUS);

            if (view == null) {
                if (nearby != null) {
                    StandView fresh = new StandView(plugin, nearby, player, content, this::assets);
                    views.put(player.getUniqueId(), fresh);
                    fresh.spawn();
                }
                return;
            }
            if (!view.layout().inRange(player.getLocation(), HIDE_RADIUS)) {
                view.despawn();
                views.remove(player.getUniqueId());
                return;
            }
            // Радиус большой, стенды стоят рядом: подошёл к другому — показываем его.
            if (nearby != null && nearby != view.layout()
                    && distance(nearby, player) + SWITCH_MARGIN < distance(view.layout(), player)) {
                view.despawn();
                StandView fresh = new StandView(plugin, nearby, player, content, this::assets);
                views.put(player.getUniqueId(), fresh);
                fresh.spawn();
                return;
            }
            double[] at = view.layout().look(player.getEyeLocation(), player.getEyeLocation().getDirection(), LOOK_DISTANCE);
            boolean onBar = at != null && view.layout().onScrollbar(at[0], at[1]);
            // на полосе прокрутки и пока её тянут строку не подсвечиваем — курсор не на ней
            view.hover(at == null || onBar || view.dragging() ? -1 : view.layout().rowAt(at[1]));
            view.hoverMenu(at != null && view.layout().onMenu(at[0], at[1]));
            view.hoverScrollbar(onBar);
            // Спрос меняется от чужих сделок — раз в две секунды освежаем цифры сами.
            if (refresh) {
                view.refresh();
            }
        }
    }

    /** Перерисовать всем, кто сейчас у стендов, — после сделки цифры устарели. */
    public void refreshAll() {
        views.forEach((id, view) -> {
            Player player = plugin.getServer().getPlayer(id);
            if (player != null) {
                player.getScheduler().run(plugin, t -> view.refresh(), null);
            }
        });
    }

    private BoardLayout nearest(Location where, double radius) {
        BoardLayout best = null;
        double bestDistance = Double.MAX_VALUE;
        for (BoardLayout stand : stands) {
            if (stand.inRange(where, radius)) {
                double d = stand.anchor().distanceSquared(where);
                if (d < bestDistance) {
                    bestDistance = d;
                    best = stand;
                }
            }
        }
        return best;
    }

    private static double distance(BoardLayout stand, Player player) {
        return stand.anchor().getWorld().equals(player.getWorld())
                ? stand.anchor().distance(player.getLocation()) : Double.MAX_VALUE;
    }

    // ---------- ввод ----------

    @EventHandler
    public void onRightClick(PlayerInteractEntityEvent event) {
        StandView view = views.get(event.getPlayer().getUniqueId());
        if (view != null && view.owns(event.getRightClicked())) {
            event.setCancelled(true);
            if (view.isScrollbar(event.getRightClicked())) {
                if (event.getHand() == EquipmentSlot.HAND) {
                    drag(event.getPlayer(), view);
                }
                return;
            }
            // клиент шлёт нажатие за каждую руку, а зажатая ПКМ повторяет его — открываем раз в полсекунды
            if (event.getHand() == EquipmentSlot.HAND && cooldown(event.getPlayer())) {
                view.click(event.getPlayer(), event.getRightClicked());
            }
        }
    }

    @EventHandler
    public void onLeftClick(EntityDamageByEntityEvent event) {
        if (!(event.getDamager() instanceof Player player)) {
            return;
        }
        StandView view = views.get(player.getUniqueId());
        if (view != null && view.owns(event.getEntity())) {
            event.setCancelled(true);
            if (view.isScrollbar(event.getEntity())) {
                drag(player, view); // ЛКМ не повторяется сама: просто переставить бегунок под курсор
                return;
            }
            if (cooldown(player)) {
                view.click(player, event.getEntity());
            }
        }
    }

    /**
     * Перетаскивание бегунка. Ванильный клиент не сообщает, зажата ли кнопка мыши, но зажатая ПКМ
     * по сущности повторяет нажатие раз в 4 тика — каждый повтор продлевает «захват». Пока он
     * длится, каждый тик ставим бегунок под точку, куда смотрит игрок: список едет за мышью
     * плавно, а не рывками раз в 4 тика. Отпустил кнопку — повторы прекратились, захват истёк.
     */
    private void drag(Player player, StandView view) {
        double[] at = view.layout().lookAnywhere(player.getEyeLocation(), player.getEyeLocation().getDirection(), LOOK_DISTANCE);
        if (at == null) {
            return;
        }
        view.dragPing(at[1] * view.layout().rowHeight());
        UUID id = player.getUniqueId();
        if (drags.containsKey(id)) {
            return;
        }
        var task = player.getScheduler().runAtFixedRate(plugin, t -> {
            StandView current = views.get(id);
            if (current != view || !view.dragging() || !player.isOnline()) {
                t.cancel();
                drags.remove(id);
                if (current == view) {
                    view.hoverScrollbar(false);
                }
                return;
            }
            double[] look = view.layout().lookAnywhere(player.getEyeLocation(), player.getEyeLocation().getDirection(), LOOK_DISTANCE * 2);
            if (look != null) {
                view.dragTo(look[1] * view.layout().rowHeight());
            }
        }, () -> drags.remove(id), 1L, 1L);
        if (task != null) {
            drags.put(id, task);
        }
    }

    /** Русские названия и ширина шрифта — из ассетов калькулятора (пусто, пока не скачались). */
    private org.webtrade.webTrade.assets.CalculatorAssets.Result assets() {
        return plugin instanceof org.webtrade.webTrade.WebTrade webTrade
                ? webTrade.calculatorAssets() : org.webtrade.webTrade.assets.CalculatorAssets.Result.empty();
    }

    /** true — можно нажимать: с прошлого нажатия прошло достаточно времени. */
    private boolean cooldown(Player player) {
        long now = System.currentTimeMillis();
        Long last = lastClick.put(player.getUniqueId(), now);
        return last == null || now - last >= CLICK_COOLDOWN_MS;
    }

    /**
     * Прокрутка колесом. Смена слота хотбара — единственный способ поймать колесо ванильным
     * клиентом. Возвращать слот после каждого щелчка нельзя: клиент крутит хотбар сам, не дожидаясь
     * сервера, и при быстрой прокрутке успевает уйти на несколько слотов, пока ответ в пути, — тогда
     * сдвиг считается не от того слота, и список дёргается назад. Поэтому во время серии щелчков
     * слот не трогаем (сдвиг точный: клиент и сервер видят одно и то же), а вернуть его на место,
     * где серия началась, — через {@link #SCROLL_SETTLE_MS} после последнего щелчка.
     */
    @EventHandler
    public void onScroll(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        StandView view = views.get(player.getUniqueId());
        if (view == null) {
            return;
        }
        // Перехватываем только когда игрок смотрит на доску, иначе рядом с ней нельзя будет
        // просто сменить предмет в руке. Смотрим на всю доску, а не в строку: промахи между
        // строк раньше съедали щелчки колеса, и прокрутка шла рывками.
        if (view.layout().look(player.getEyeLocation(), player.getEyeLocation().getDirection(), LOOK_DISTANCE) == null) {
            scrollHome.remove(player.getUniqueId()); // отвернулся и сам выбирает слот — не перебиваем
            scrollDirection.remove(player.getUniqueId());
            return;
        }

        UUID id = player.getUniqueId();
        int previous = event.getPreviousSlot();
        int delta = event.getNewSlot() - previous;
        // Хотбар закольцован: переход 8→0 это шаг вперёд, а не восемь шагов назад.
        if (delta > 4) {
            delta -= 9;
        } else if (delta < -4) {
            delta += 9;
        }
        if (delta == 0) {
            return;
        }
        // Клиент шлёт слот раз в тик, только итог. Сильный рывок колеса — 5–6 щелчков за тик — по
        // кругу из 9 слотов выглядит короче в обратную сторону (6 вниз = 3 вверх), и список отскакивал
        // назад. Поэтому большой скачок против направления серии считаем рывком в её сторону, а
        // маленький (1–2 щелчка) — настоящим разворотом: иначе крутнуть назад было нельзя, и у края
        // списка стенд будто застревал.
        Integer direction = scrollDirection.get(id);
        if (direction != null && Integer.signum(delta) != direction && Math.abs(delta) >= 3) {
            delta += 9 * direction;
        }
        scrollDirection.put(id, Integer.signum(delta));

        view.scroll(delta);
        boolean burstStarted = scrollHome.putIfAbsent(id, previous) == null;
        lastScroll.put(id, System.currentTimeMillis());
        if (burstStarted) {
            settleScroll(player);
        }
    }

    /** Когда колесо затихло — вернуть в руку тот слот, с которого игрок начал крутить. */
    private void settleScroll(Player player) {
        player.getScheduler().runDelayed(plugin, t -> {
            UUID id = player.getUniqueId();
            long quiet = System.currentTimeMillis() - lastScroll.getOrDefault(id, 0L);
            if (quiet < SCROLL_SETTLE_MS) {
                settleScroll(player);
                return;
            }
            Integer home = scrollHome.remove(id);
            lastScroll.remove(id);
            scrollDirection.remove(id);
            if (home != null && player.isOnline()) {
                player.getInventory().setHeldItemSlot(home);
            }
        }, null, 3L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        scrollHome.remove(event.getPlayer().getUniqueId());
        lastScroll.remove(event.getPlayer().getUniqueId());
        scrollDirection.remove(event.getPlayer().getUniqueId());
        drags.remove(event.getPlayer().getUniqueId());
        StandView view = views.remove(event.getPlayer().getUniqueId());
        if (view != null) {
            view.despawn();
        }
    }

    // ---------- управление стендами ----------

    /** Ставит стенд перед игроком, лицом к нему. Якорь — нижняя строка списка. */
    public void create(Player admin) {
        Location anchor = admin.getLocation().clone();
        // Список растёт вверх от якоря: якорь ниже глаз, чтобы доска стояла вокруг взгляда,
        // а не уходила вверх — иначе вблизи приходится задирать голову и всё заваливается.
        anchor.setY(anchor.getY() + 0.9);
        anchor.setYaw(admin.getLocation().getYaw() + 180f);
        anchor.setPitch(0f);
        // Отодвигаем вперёд, чтобы стенд не оказался внутри игрока.
        anchor.add(admin.getLocation().getDirection().setY(0).normalize().multiply(4.0));

        BoardLayout stand = BoardLayout.of(anchor, DEFAULT_ROWS);
        stands.add(stand);
        save();
        admin.sendMessage(Ui.text("Стенд поставлен. Всего стендов: " + stands.size(),
                NamedTextColor.GREEN));
        center(stand, admin::sendMessage);
    }

    // ---------- выравнивание ----------

    /** Как далеко в стороны от края доски ищем стену, в блоках. */
    private static final double CENTER_REACH = 4.0;
    /** Шаг поиска края стены: сотая блока — глазу уже не видно. */
    private static final double CENTER_STEP = 0.01;

    /** Выровнять ближайший к админу стенд. false — рядом стендов нет. */
    public boolean centerNearest(Player admin) {
        BoardLayout nearby = nearest(admin.getLocation(), HIDE_RADIUS);
        if (nearby == null) {
            return false;
        }
        center(nearby, admin::sendMessage);
        return true;
    }

    /** Выровнять все стенды (из консоли). */
    public void centerAll(java.util.function.Consumer<net.kyori.adventure.text.Component> report) {
        new ArrayList<>(stands).forEach(stand -> center(stand, report));
    }

    /**
     * Ставит стенд ровно: поворот — точно по стороне света, а сама доска — посередине между
     * стенами слева и справа. Раньше стенд смотрел туда же, куда админ (358.95° вместо 0°), и
     * стоял там, где админ стоял: один край уходил в стену, с другого — щель.
     * <p>
     * Стену ищем по форме блоков, а не по «целый/не целый»: стекло-панель, забор, ступенька
     * считаются по тому, что у них правда занимает место. Если стены нет с одной из сторон в
     * пределах {@link #CENTER_REACH} от края доски, стенд остаётся на месте по ширине, но
     * поворот всё равно выравнивается.
     * <p>
     * Folia: блоки читаем в регионе, которому принадлежит стенд.
     */
    private void center(BoardLayout stand, java.util.function.Consumer<net.kyori.adventure.text.Component> report) {
        Location anchor = stand.anchor();
        plugin.getServer().getRegionScheduler().run(plugin, anchor, t -> {
            Location straight = anchor.clone();
            straight.setYaw(Math.round(anchor.getYaw() / 90f) * 90f % 360f);
            BoardLayout squared = new BoardLayout(straight, stand.rows(), stand.rowHeight(), stand.width());

            double half = squared.panelWidth() / 2;
            double left = wallDistance(squared, -1, half + CENTER_REACH);
            double right = wallDistance(squared, 1, half + CENTER_REACH);
            String how;
            if (Double.isNaN(left) || Double.isNaN(right)) {
                how = "стены с обеих сторон не нашлось — выровнен только поворот";
            } else {
                double shift = (right - left) / 2;
                straight.add(squared.rightward().multiply(shift));
                double opening = left + right;
                how = String.format(Locale.ROOT, "проём %.2f, доска %.2f блока, сдвиг %.2f", opening, squared.panelWidth(), shift)
                        + (opening < squared.panelWidth() ? " — проём уже доски, края зайдут в стены" : "");
            }
            BoardLayout fixed = new BoardLayout(straight, stand.rows(), stand.rowHeight(), stand.width());
            int index = stands.indexOf(stand);
            if (index < 0) {
                return; // стенд успели убрать
            }
            stands.set(index, fixed);
            save();
            // тем, кто сейчас у этого стенда, пересоздаём доску на новом месте
            views.forEach((id, view) -> {
                if (view.layout() == stand) {
                    Player viewer = plugin.getServer().getPlayer(id);
                    if (viewer != null) {
                        viewer.getScheduler().run(plugin, s -> {
                            if (views.remove(id, view)) {
                                view.despawn();
                            }
                        }, null);
                    }
                }
            });
            report.accept(Ui.text("Стенд выровнен: " + how + ".", NamedTextColor.GREEN));
        });
    }

    /**
     * Расстояние от середины доски до ближайшей стены в сторону side (-1 влево, +1 вправо),
     * или NaN, если до maxDistance стены нет. Пробуем всю высоту доски и её толщину.
     */
    private static double wallDistance(BoardLayout stand, int side, double maxDistance) {
        World world = stand.anchor().getWorld();
        double bottom = stand.panel().getY();
        double top = bottom + stand.panelHeight();
        org.bukkit.util.Vector across = stand.rightward().multiply(side);
        org.bukkit.util.Vector normal = stand.normal();
        for (double d = 0; d <= maxDistance; d += CENTER_STEP) {
            for (double y = bottom + 0.02; y <= top - 0.02; y += 0.25) {
                for (double depth : new double[]{0.02, -0.04, -0.09}) {
                    Location point = stand.anchor().clone().add(across.clone().multiply(d)).add(normal.clone().multiply(depth));
                    point.setY(y);
                    if (solidAt(world, point)) {
                        return d;
                    }
                }
            }
        }
        return Double.NaN;
    }

    private static boolean solidAt(World world, Location point) {
        org.bukkit.block.Block block = world.getBlockAt(point);
        org.bukkit.util.Vector inside = point.toVector().subtract(block.getLocation().toVector());
        for (org.bukkit.util.BoundingBox box : block.getCollisionShape().getBoundingBoxes()) {
            if (box.contains(inside)) {
                return true;
            }
        }
        return false;
    }

    public boolean removeNearest(Player admin) {
        BoardLayout nearby = nearest(admin.getLocation(), HIDE_RADIUS);
        if (nearby == null) {
            return false;
        }
        stands.remove(nearby);
        // Тем, кто на него сейчас смотрит, стенд надо убрать сразу.
        new ArrayList<>(views.entrySet()).forEach(entry -> {
            entry.getValue().despawn();
            views.remove(entry.getKey());
        });
        save();
        return true;
    }

    public int count() {
        return stands.size();
    }

    // ---------- хранение ----------

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection root = yaml.getConfigurationSection("stands");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            ConfigurationSection sec = root.getConfigurationSection(key);
            if (sec == null) {
                continue;
            }
            World world = plugin.getServer().getWorld(sec.getString("world", ""));
            if (world == null) {
                plugin.getLogger().warning("stands.yml: мир для стенда " + key + " не найден.");
                continue;
            }
            Location anchor = new Location(world,
                    sec.getDouble("x"), sec.getDouble("y"), sec.getDouble("z"),
                    (float) sec.getDouble("yaw"), 0f);
            stands.add(new BoardLayout(anchor,
                    sec.getInt("rows", DEFAULT_ROWS),
                    sec.getDouble("row-height", BoardLayout.DEFAULT_ROW_HEIGHT),
                    sec.getDouble("width", BoardLayout.DEFAULT_WIDTH)));
        }
        plugin.getLogger().info("Загружено стендов: " + stands.size());
    }

    public void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (int i = 0; i < stands.size(); i++) {
            BoardLayout stand = stands.get(i);
            String base = "stands." + i;
            yaml.set(base + ".world", stand.anchor().getWorld().getName());
            yaml.set(base + ".x", stand.anchor().getX());
            yaml.set(base + ".y", stand.anchor().getY());
            yaml.set(base + ".z", stand.anchor().getZ());
            yaml.set(base + ".yaw", (double) stand.anchor().getYaw());
            yaml.set(base + ".rows", stand.rows());
            yaml.set(base + ".row-height", stand.rowHeight());
            yaml.set(base + ".width", stand.width());
        }
        try {
            org.webtrade.webTrade.SafeFiles.save(yaml, file);
        } catch (IOException e) {
            plugin.getLogger().severe("Не удалось сохранить stands.yml: " + e.getMessage());
        }
    }
}
