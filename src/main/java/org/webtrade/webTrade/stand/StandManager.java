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
    private static final long TASK_PERIOD = 10L;

    private static final int DEFAULT_ROWS = BoardLayout.DEFAULT_ROWS;

    private final Plugin plugin;
    private final StandContent content;
    private final File file;

    private final List<BoardLayout> stands = new ArrayList<>();
    private final Map<UUID, StandView> views = new HashMap<>();

    private int taskId = -1;
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
        taskId = plugin.getServer().getScheduler().runTaskTimer(plugin, this::tick,
                TASK_PERIOD, TASK_PERIOD).getTaskId();
    }

    public void stop() {
        if (taskId != -1) {
            plugin.getServer().getScheduler().cancelTask(taskId);
            taskId = -1;
        }
        views.values().forEach(StandView::despawn);
        views.clear();
    }

    /** Раз в полсекунды решаем, кому стенд показать, кому убрать и что подсветить. */
    private void tick() {
        for (Player player : plugin.getServer().getOnlinePlayers()) {
            StandView view = views.get(player.getUniqueId());
            BoardLayout nearby = nearest(player.getLocation(), SHOW_RADIUS);

            if (view == null) {
                if (nearby != null) {
                    StandView fresh = new StandView(plugin, nearby, player, content);
                    views.put(player.getUniqueId(), fresh);
                    fresh.spawn();
                }
                continue;
            }
            if (nearest(player.getLocation(), HIDE_RADIUS) == null) {
                view.despawn();
                views.remove(player.getUniqueId());
                continue;
            }
            view.hover(player.getTargetEntity(LOOK_DISTANCE));
        }
        // Спрос меняется от чужих сделок — раз в две секунды освежаем цифры сами.
        if (++ticks % 4 == 0) {
            refreshAll();
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

    /** Перерисовать всем, кто сейчас у стендов, — после сделки цифры устарели. */
    public void refreshAll() {
        views.values().forEach(StandView::refresh);
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
            view.click(event.getPlayer(), event.getRightClicked());
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
            view.click(player, event.getEntity());
        }
    }

    /**
     * Прокрутка колесом. Смена слота хотбара — единственный способ поймать колесо
     * ванильным клиентом, поэтому у стенда мы её перехватываем и возвращаем слот назад.
     */
    @EventHandler
    public void onScroll(PlayerItemHeldEvent event) {
        Player player = event.getPlayer();
        StandView view = views.get(player.getUniqueId());
        if (view == null) {
            return;
        }
        // Перехватываем только когда игрок смотрит на стенд, иначе рядом с ним
        // нельзя будет просто сменить предмет в руке.
        Entity looking = player.getTargetEntity(LOOK_DISTANCE);
        if (looking == null || !view.owns(looking)) {
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

        event.setCancelled(true);
        // Клиент уже подвинул выделение у себя — возвращаем слот на место следующим тиком.
        plugin.getServer().getScheduler().runTask(plugin,
                () -> player.getInventory().setHeldItemSlot(previous));
        view.scroll(delta > 0 ? 1 : -1);
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent event) {
        StandView view = views.remove(event.getPlayer().getUniqueId());
        if (view != null) {
            view.despawn();
        }
    }

    // ---------- управление стендами ----------

    /** Ставит стенд перед игроком, лицом к нему. Якорь — нижняя строка списка. */
    public void create(Player admin) {
        Location anchor = admin.getLocation().clone();
        // Список растёт вверх от якоря, поэтому якорь ставим примерно на уровень глаз.
        anchor.setY(anchor.getY() + 1.7);
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
