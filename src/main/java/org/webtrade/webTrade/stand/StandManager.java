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
    private static final double SHOW_RADIUS = 10.0;
    private static final double HIDE_RADIUS = 14.0;
    /** Как далеко ищем сущность, на которую смотрит игрок. */
    private static final int LOOK_DISTANCE = 8;
    /** Подсветку строки под взглядом обновляем часто, иначе она отстаёт от мыши. */
    private static final int TASK_PERIOD = 4;
    private static final long CLICK_COOLDOWN_MS = 500;
    /** Через сколько тишины после последнего щелчка колеса хотбар возвращается на место. */
    private static final long SCROLL_SETTLE_MS = 250;

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
            if (nearest(player.getLocation(), HIDE_RADIUS) == null) {
                view.despawn();
                views.remove(player.getUniqueId());
                return;
            }
            view.hover(lookedRow(player, view));
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
        for (BoardLayout stand : stands) {
            if (stand.inRange(where, radius)) {
                return stand;
            }
        }
        return null;
    }

    // ---------- ввод ----------

    @EventHandler
    public void onRightClick(PlayerInteractEntityEvent event) {
        StandView view = views.get(event.getPlayer().getUniqueId());
        if (view != null && view.owns(event.getRightClicked())) {
            event.setCancelled(true);
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
            if (cooldown(player)) {
                view.click(player, event.getEntity());
            }
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

    /** Строка, на которую игрок смотрит (-1 — мимо списка). Считается геометрически, без хитбоксов. */
    private static int lookedRow(Player player, StandView view) {
        double[] at = view.layout().look(player.getEyeLocation(), player.getEyeLocation().getDirection(), LOOK_DISTANCE);
        return at == null ? -1 : view.layout().rowAt(at[1]);
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
            return;
        }

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

        view.scroll(delta);
        UUID id = player.getUniqueId();
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
            if (home != null && player.isOnline()) {
                player.getInventory().setHeldItemSlot(home);
            }
        }, null, 3L);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        scrollHome.remove(event.getPlayer().getUniqueId());
        lastScroll.remove(event.getPlayer().getUniqueId());
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

        stands.add(BoardLayout.of(anchor, DEFAULT_ROWS));
        save();
        admin.sendMessage(Ui.text("Стенд поставлен. Всего стендов: " + stands.size(),
                NamedTextColor.GREEN));
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
            yaml.save(file);
        } catch (IOException e) {
            plugin.getLogger().severe("Не удалось сохранить stands.yml: " + e.getMessage());
        }
    }
}
