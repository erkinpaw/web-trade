package org.webtrade.webTrade.resource;

import org.bukkit.Material;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.logging.Logger;

/**
 * Каталог торгуемых ресурсов. Торгуются только СТАВЯЩИЕСЯ стакающиеся предметы
 * (блоки, лампы, стёкла, кактусы, цветы, горшки и т.п.).
 * При первом запуске генерирует resources.yml со всеми подходящими материалами и ценой по
 * умолчанию; далее цены правятся вручную в этом файле.
 */
public final class ResourceRegistry {

    private static final double DEFAULT_PRICE_PER_STACK = 0.1;

    /** Ручные стартовые цены для заметных ресурсов (за стак 64 шт.). */
    private static final Map<Material, Double> SEED_PRICES = new LinkedHashMap<>();

    static {
        seed(0.05, "DIRT", "SAND", "GRAVEL", "COARSE_DIRT", "RED_SAND");
        seed(0.1, "STONE", "COBBLESTONE", "DEEPSLATE", "COBBLED_DEEPSLATE", "NETHERRACK",
                "GRANITE", "DIORITE", "ANDESITE", "TUFF");
        seed(0.2, "OAK_LOG", "SPRUCE_LOG", "BIRCH_LOG", "OAK_PLANKS", "SPRUCE_PLANKS",
                "GLASS", "WHITE_STAINED_GLASS", "GLOWSTONE", "REDSTONE_LAMP");
        seed(0.3, "COAL_BLOCK", "TERRACOTTA", "WHITE_TERRACOTTA");
        seed(0.4, "REDSTONE_BLOCK");
        seed(0.5, "QUARTZ_BLOCK", "SMOOTH_QUARTZ");
        seed(160.0, "DIAMOND_BLOCK"); // ≈ 2.5 ара за 1 блок
        seed(64.0, "GOLD_BLOCK");
        seed(32.0, "IRON_BLOCK");
    }

    private static void seed(double pricePerStack, String... materials) {
        for (String name : materials) {
            Material material = Material.matchMaterial(name);
            if (material != null) {
                SEED_PRICES.put(material, pricePerStack);
            }
        }
    }

    private final Map<Material, ResourceType> resources = new LinkedHashMap<>();

    public ResourceRegistry(File dataFolder, Logger logger) {
        File file = new File(dataFolder, "resources.yml");
        if (!file.exists()) {
            generate(file, logger);
        }
        load(file, logger);
    }

    /** Является ли материал кандидатом на торговлю: ставящийся, стакающийся, не воздух. */
    public static boolean isTradeableMaterial(Material material) {
        return !material.isLegacy()
                && material.isItem()
                && material.isBlock()
                && !material.isAir()
                && material.getMaxStackSize() > 1;
    }

    private void generate(File file, Logger logger) {
        // Сортируем по имени для читаемого файла.
        TreeMap<String, Double> sorted = new TreeMap<>();
        for (Material material : Material.values()) {
            if (!isTradeableMaterial(material)) {
                continue;
            }
            double price = SEED_PRICES.getOrDefault(material, DEFAULT_PRICE_PER_STACK);
            sorted.put(material.name(), price);
        }

        YamlConfiguration yaml = new YamlConfiguration();
        yaml.options().setHeader(java.util.List.of(
                "Каталог торгуемых ресурсов WebTrade.",
                "Цена указана ЗА СТАК (64 шт.) в арах. Цена за штуку = цена / 64.",
                "Удалите строку, чтобы убрать ресурс из торговли."));
        for (Map.Entry<String, Double> entry : sorted.entrySet()) {
            yaml.set("resources." + entry.getKey(), entry.getValue());
        }
        try {
            yaml.save(file);
            logger.info("Создан resources.yml с " + sorted.size() + " ресурсами.");
        } catch (IOException e) {
            logger.severe("Не удалось создать resources.yml: " + e.getMessage());
        }
    }

    private void load(File file, Logger logger) {
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        ConfigurationSection section = yaml.getConfigurationSection("resources");
        if (section == null) {
            logger.warning("В resources.yml нет секции resources.");
            return;
        }
        for (String key : section.getKeys(false)) {
            Material material = Material.matchMaterial(key);
            if (material == null || !isTradeableMaterial(material)) {
                logger.warning("resources.yml: пропущен ресурс " + key);
                continue;
            }
            double price = section.getDouble(key, DEFAULT_PRICE_PER_STACK);
            resources.put(material, new ResourceType(material, price));
        }
        logger.info("Загружено торгуемых ресурсов: " + resources.size());
    }

    public boolean isTradeable(Material material) {
        return resources.containsKey(material);
    }

    public ResourceType get(Material material) {
        return resources.get(material);
    }

    public java.util.Collection<ResourceType> all() {
        return resources.values();
    }
}
