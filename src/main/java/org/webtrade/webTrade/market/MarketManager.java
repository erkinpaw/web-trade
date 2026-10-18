package org.webtrade.webTrade.market;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;
import org.webtrade.webTrade.account.AccountManager;
import org.webtrade.webTrade.resource.ResourceRegistry;
import org.webtrade.webTrade.resource.ResourceType;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;
import java.util.logging.Logger;

/**
 * Биржа: контейнеры (спрос), общий склад (предложение) и движок сведения.
 * <p>
 * Правила: сведение строго FIFO — кто раньше пришёл, тот первый.
 * Ресурс, на который сейчас нет спроса, лежит на складе за добытчиком и оплачивается
 * позже, когда спрос появится.
 * <p>
 * Все изменяющие методы синхронизированы: их дёргают и веб-потоки, и главный поток.
 */
public final class MarketManager {

    private final File containersFile;
    private final File warehouseFile;
    private final ResourceRegistry resources;
    private final AccountManager accounts;
    private final Logger logger;
    private final double feePercent;

    private final ConcurrentHashMap<Integer, Container> containers = new ConcurrentHashMap<>();
    /** Склад в порядке поступления — это и есть очередь FIFO. */
    private final List<SupplyEntry> warehouse = new ArrayList<>();
    private final AtomicInteger nextId = new AtomicInteger(1);

    private volatile Consumer<Payout> payoutListener = payout -> { };

    public MarketManager(File dataFolder, ResourceRegistry resources, AccountManager accounts,
                         Logger logger, double feePercent) {
        this.containersFile = new File(dataFolder, "containers.yml");
        this.warehouseFile = new File(dataFolder, "warehouse.yml");
        this.resources = resources;
        this.accounts = accounts;
        this.logger = logger;
        this.feePercent = feePercent;
        loadContainers();
        loadWarehouse();
    }

    /** Событие оплаты добытчику — чтобы уведомить его, если он в сети. */
    public record Payout(UUID seller, Material material, int amount, double money) {
    }

    public void setPayoutListener(Consumer<Payout> listener) {
        this.payoutListener = listener == null ? payout -> { } : listener;
    }

    public record CreateResult(boolean ok, String message, int containerId, double cost) {
        static CreateResult fail(String message) {
            return new CreateResult(false, message, -1, 0.0);
        }
    }

    /** Итог сдачи ресурсов: сколько ушло в спрос, сколько легло на склад, сколько заплатили. */
    public record SupplyResult(int matched, int stored, double paid) {
    }

    // ---------- цены ----------

    public boolean isTradeable(Material material) {
        return resources.isTradeable(material);
    }

    public double price(Map<Material, Integer> request) {
        double total = 0.0;
        for (Map.Entry<Material, Integer> entry : request.entrySet()) {
            ResourceType type = resources.get(entry.getKey());
            if (type != null) {
                total += type.pricePerUnit() * entry.getValue();
            }
        }
        return total;
    }

    /** Выплата добытчику за amount единиц с учётом комиссии биржи. */
    private double payoutFor(ResourceType type, int amount) {
        double gross = type.pricePerUnit() * amount;
        return gross * (1.0 - feePercent / 100.0);
    }

    // ---------- спрос: создание контейнера ----------

    /**
     * Создаёт контейнер и сразу списывает оплату. Только после оплаты заявка попадает в спрос.
     * Если на складе уже лежит нужное — контейнер тут же частично набирается,
     * а те, кто это сдал, получают деньги.
     */
    public CreateResult create(UUID owner, String ownerName, String title,
                               Map<Material, Integer> request) {
        List<Payout> payouts = new ArrayList<>();
        CreateResult result;

        synchronized (this) {
            LinkedHashMap<Material, Container.Line> lines = new LinkedHashMap<>();
            for (Map.Entry<Material, Integer> entry : request.entrySet()) {
                Material material = entry.getKey();
                int amount = entry.getValue() == null ? 0 : entry.getValue();
                if (amount <= 0) {
                    continue;
                }
                if (!resources.isTradeable(material)) {
                    return CreateResult.fail("Ресурс не торгуется: " + material.name());
                }
                lines.put(material, new Container.Line(amount, 0, 0));
            }
            if (lines.isEmpty()) {
                return CreateResult.fail("Контейнер пуст — укажите хотя бы один ресурс.");
            }

            Map<Material, Integer> amounts = new HashMap<>();
            lines.forEach((material, line) -> amounts.put(material, line.requested()));
            double cost = price(amounts);

            if (!accounts.withdraw(owner, cost)) {
                return CreateResult.fail("Недостаточно средств: нужно " + money(cost) + " ар.");
            }

            int id = nextId.getAndIncrement();
            Container container = new Container(id, owner, trimName(title),
                    System.currentTimeMillis(), Container.Status.ACTIVE, lines);
            containers.put(id, container);

            // то, что уже лежит на складе, сразу идёт в этот контейнер
            fillFromWarehouse(container, payouts);

            accounts.save();
            saveContainers();
            saveWarehouse();
            logger.info("Создан контейнер #" + id + " игроком " + ownerName + " на " + money(cost) + " ар.");
            result = new CreateResult(true, "Контейнер #" + id + " создан и оплачен.", id, cost);
        }

        payouts.forEach(payoutListener);
        return result;
    }

    /** Название режем по длине, чтобы им нельзя было развалить вёрстку списков. */
    private static String trimName(String title) {
        if (title == null) {
            return "";
        }
        String trimmed = title.trim();
        return trimmed.length() <= Container.MAX_NAME
                ? trimmed
                : trimmed.substring(0, Container.MAX_NAME);
    }

    /** Набирает контейнер из склада (FIFO) и платит тем, кто это сдал. */
    private void fillFromWarehouse(Container container, List<Payout> payouts) {
        for (Map.Entry<Material, Container.Line> entry : container.lines().entrySet()) {
            Material material = entry.getKey();
            Container.Line line = entry.getValue();
            ResourceType type = resources.get(material);
            if (type == null) {
                continue;
            }
            Iterator<SupplyEntry> iterator = warehouse.iterator();
            while (iterator.hasNext() && line.outstanding() > 0) {
                SupplyEntry supply = iterator.next();
                if (supply.material() != material) {
                    continue;
                }
                int take = Math.min(supply.amount(), line.outstanding());
                if (take <= 0) {
                    continue;
                }
                line.addCollected(take);
                supply.reduce(take);

                double money = payoutFor(type, take);
                accounts.deposit(supply.seller(), null, money);
                payouts.add(new Payout(supply.seller(), material, take, money));

                if (supply.amount() == 0) {
                    iterator.remove();
                }
            }
        }
    }

    // ---------- предложение: сдача ресурсов ----------

    /**
     * Добытчик сдаёт ресурсы. Сначала закрываем ими текущий спрос (FIFO по контейнерам)
     * и сразу платим; остаток кладём на склад ждать спроса.
     */
    public SupplyResult supply(UUID seller, String sellerName, Material material, int amount) {
        ResourceType type = resources.get(material);
        if (type == null || amount <= 0) {
            return null;
        }
        List<Payout> payouts = new ArrayList<>();
        SupplyResult result;

        synchronized (this) {
            int remaining = amount;
            int matched = 0;

            for (Container container : activeByAge()) {
                if (remaining <= 0) {
                    break;
                }
                Container.Line line = container.line(material);
                if (line == null || line.outstanding() <= 0) {
                    continue;
                }
                int take = Math.min(line.outstanding(), remaining);
                line.addCollected(take);
                remaining -= take;
                matched += take;
            }

            double paid = 0.0;
            if (matched > 0) {
                paid = payoutFor(type, matched);
                accounts.deposit(seller, sellerName, paid);
            }
            if (remaining > 0) {
                warehouse.add(new SupplyEntry(seller, material, remaining, System.currentTimeMillis()));
            }

            accounts.save();
            saveContainers();
            saveWarehouse();
            result = new SupplyResult(matched, remaining, paid);
        }

        payouts.forEach(payoutListener);
        return result;
    }

    // ---------- выдача ресурсов владельцу ----------

    /** Есть ли в контейнере что-то собранное, но ещё не выданное. */
    public synchronized boolean hasReady(Container container) {
        return container.lines().values().stream().anyMatch(line -> line.readyToWithdraw() > 0);
    }

    /**
     * Списывает из контейнера то, что игрок забрал.
     * <p>
     * Вызывать ТОЛЬКО после того, как предметы уже у игрока: списание идёт следом за
     * фактической выдачей, чтобы порция не задвоилась и не пропала.
     *
     * @return сколько реально списано (может быть меньше запрошенного)
     */
    public synchronized int withdraw(Container container, Material material, int amount) {
        Container.Line line = container.line(material);
        if (line == null || amount <= 0) {
            return 0;
        }
        int taken = Math.min(amount, line.readyToWithdraw());
        if (taken <= 0) {
            return 0;
        }
        line.addWithdrawn(taken);
        forgetIfSpent(container);
        saveContainers();
        return taken;
    }

    /**
     * Отменяет контейнер: за несобранное возвращает деньги, спрос снимается.
     * Уже собранное остаётся — его ещё можно забрать, поэтому контейнер исчезнет
     * только когда из него всё вынесут.
     *
     * @return сколько ар вернулось
     */
    public synchronized double cancel(Container container) {
        if (container.status() != Container.Status.ACTIVE) {
            return 0.0;
        }
        double refund = 0.0;
        for (Map.Entry<Material, Container.Line> entry : container.lines().entrySet()) {
            ResourceType type = resources.get(entry.getKey());
            if (type != null) {
                refund += type.pricePerUnit() * entry.getValue().outstanding();
            }
        }
        container.setStatus(Container.Status.CANCELLED);
        if (refund > 0) {
            accounts.deposit(container.owner(), null, refund);
            accounts.save();
        }
        forgetIfSpent(container);
        saveContainers();
        logger.info("Контейнер #" + container.id() + " отменён, возвращено " + money(refund) + " ар.");
        return refund;
    }

    /**
     * Забирает контейнер из списка, когда он больше не нужен: всё вынесено, а спроса
     * по нему нет. Историю не храним — игрокам она не интересна.
     */
    private void forgetIfSpent(Container container) {
        boolean nothingLeft = container.lines().values().stream()
                .allMatch(line -> line.readyToWithdraw() == 0);
        boolean nothingExpected = container.status() == Container.Status.CANCELLED
                || container.isFilled();
        if (nothingLeft && nothingExpected) {
            containers.remove(container.id());
        }
    }

    /** Есть ли в контейнере хоть что-то, что можно забрать прямо сейчас. */
    public synchronized boolean isEmptyForOwner(Container container) {
        return container.lines().values().stream().allMatch(line -> line.readyToWithdraw() == 0);
    }

    // ---------- чтение ----------

    /** Агрегированный спрос по активным контейнерам, от большего к меньшему. */
    public synchronized LinkedHashMap<Material, Integer> demand() {
        Map<Material, Integer> totals = new HashMap<>();
        for (Container container : containers.values()) {
            if (container.status() != Container.Status.ACTIVE) {
                continue;
            }
            container.lines().forEach((material, line) -> {
                int outstanding = line.outstanding();
                if (outstanding > 0) {
                    totals.merge(material, outstanding, Integer::sum);
                }
            });
        }
        LinkedHashMap<Material, Integer> sorted = new LinkedHashMap<>();
        totals.entrySet().stream()
                .sorted(Map.Entry.<Material, Integer>comparingByValue().reversed())
                .forEach(e -> sorted.put(e.getKey(), e.getValue()));
        return sorted;
    }

    /** Что у игрока лежит на складе в ожидании спроса. */
    public synchronized LinkedHashMap<Material, Integer> stockOf(UUID seller) {
        LinkedHashMap<Material, Integer> totals = new LinkedHashMap<>();
        for (SupplyEntry entry : warehouse) {
            if (entry.seller().equals(seller) && entry.amount() > 0) {
                totals.merge(entry.material(), entry.amount(), Integer::sum);
            }
        }
        return totals;
    }

    public List<Container> containersOf(UUID owner) {
        List<Container> result = new ArrayList<>();
        for (Container container : containers.values()) {
            if (container.owner().equals(owner)) {
                result.add(container);
            }
        }
        result.sort(Comparator.comparingLong(Container::createdAt).reversed());
        return result;
    }

    public Container get(int id) {
        return containers.get(id);
    }

    /** Активные контейнеры в порядке создания — очередь FIFO для сведения. */
    public List<Container> activeByAge() {
        List<Container> result = new ArrayList<>();
        for (Container container : containers.values()) {
            if (container.status() == Container.Status.ACTIVE) {
                result.add(container);
            }
        }
        result.sort(Comparator.comparingLong(Container::createdAt));
        return result;
    }

    public static String money(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    // ---------- персистентность ----------

    private void loadContainers() {
        if (!containersFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(containersFile);
        nextId.set(yaml.getInt("next-id", 1));
        ConfigurationSection root = yaml.getConfigurationSection("containers");
        if (root == null) {
            return;
        }
        for (String key : root.getKeys(false)) {
            try {
                ConfigurationSection sec = root.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                int id = Integer.parseInt(key);
                UUID owner = UUID.fromString(sec.getString("owner", ""));
                long created = sec.getLong("created", System.currentTimeMillis());
                Container.Status status = Container.Status.valueOf(sec.getString("status", "ACTIVE"));

                LinkedHashMap<Material, Container.Line> lines = new LinkedHashMap<>();
                ConfigurationSection items = sec.getConfigurationSection("items");
                if (items != null) {
                    for (String materialName : items.getKeys(false)) {
                        Material material = Material.matchMaterial(materialName);
                        if (material == null) {
                            continue;
                        }
                        lines.put(material, new Container.Line(
                                items.getInt(materialName + ".requested", 0),
                                items.getInt(materialName + ".collected", 0),
                                items.getInt(materialName + ".withdrawn", 0)));
                    }
                }
                containers.put(id, new Container(id, owner, sec.getString("name", ""),
                        created, status, lines));
            } catch (IllegalArgumentException e) {
                logger.warning("containers.yml: пропущен контейнер " + key + " (" + e.getMessage() + ")");
            }
        }
        // В старых сохранениях выбранные контейнеры оставались со статусом COMPLETED.
        // Историю мы не храним, поэтому подчищаем их сразу при загрузке.
        int before = containers.size();
        new ArrayList<>(containers.values()).forEach(this::forgetIfSpent);
        int dropped = before - containers.size();
        logger.info("Загружено контейнеров: " + containers.size()
                + (dropped > 0 ? " (выбранных убрано: " + dropped + ")" : ""));
    }

    public synchronized void saveContainers() {
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("next-id", nextId.get());
        for (Container container : containers.values()) {
            String base = "containers." + container.id();
            yaml.set(base + ".owner", container.owner().toString());
            if (!container.name().isBlank()) {
                yaml.set(base + ".name", container.name());
            }
            yaml.set(base + ".created", container.createdAt());
            yaml.set(base + ".status", container.status().name());
            container.lines().forEach((material, line) -> {
                String path = base + ".items." + material.name();
                yaml.set(path + ".requested", line.requested());
                yaml.set(path + ".collected", line.collected());
                yaml.set(path + ".withdrawn", line.withdrawn());
            });
        }
        try {
            yaml.save(containersFile);
        } catch (IOException e) {
            logger.severe("Не удалось сохранить containers.yml: " + e.getMessage());
        }
    }

    private void loadWarehouse() {
        if (!warehouseFile.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(warehouseFile);
        for (Map<?, ?> raw : yaml.getMapList("entries")) {
            try {
                UUID seller = UUID.fromString(String.valueOf(raw.get("seller")));
                Material material = Material.matchMaterial(String.valueOf(raw.get("material")));
                int amount = Integer.parseInt(String.valueOf(raw.get("amount")));
                long created = Long.parseLong(String.valueOf(raw.get("created")));
                if (material != null && amount > 0) {
                    warehouse.add(new SupplyEntry(seller, material, amount, created));
                }
            } catch (RuntimeException e) {
                logger.warning("warehouse.yml: пропущена запись (" + e.getMessage() + ")");
            }
        }
        warehouse.sort(Comparator.comparingLong(SupplyEntry::createdAt));
        logger.info("Загружено записей склада: " + warehouse.size());
    }

    public synchronized void saveWarehouse() {
        List<Map<String, Object>> entries = new ArrayList<>();
        for (SupplyEntry entry : warehouse) {
            if (entry.amount() <= 0) {
                continue;
            }
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("seller", entry.seller().toString());
            map.put("material", entry.material().name());
            map.put("amount", entry.amount());
            map.put("created", entry.createdAt());
            entries.add(map);
        }
        YamlConfiguration yaml = new YamlConfiguration();
        yaml.set("entries", entries);
        try {
            yaml.save(warehouseFile);
        } catch (IOException e) {
            logger.severe("Не удалось сохранить warehouse.yml: " + e.getMessage());
        }
    }

    public void save() {
        saveContainers();
        saveWarehouse();
    }
}
