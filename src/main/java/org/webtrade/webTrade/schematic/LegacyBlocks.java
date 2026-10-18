package org.webtrade.webTrade.schematic;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.material.MaterialData;

import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/**
 * Таблица перевода старых числовых ID блоков (формат .schematic до 1.13) в современные материалы.
 * <p>
 * Строится один раз при старте в главном потоке (использует Bukkit API), дальше это просто
 * массив — читать можно из любого потока.
 */
public final class LegacyBlocks {

    private static final int MAX_ID = 256;
    private static final int MAX_DATA = 16;

    /** [id][data] -> современный материал (или null, если такого нет). */
    private final Material[][] table = new Material[MAX_ID][MAX_DATA];

    @SuppressWarnings("deprecation") // работа с legacy-материалами по определению deprecated
    public LegacyBlocks(Logger logger) {
        Map<Integer, Material> byId = new HashMap<>();
        for (Material material : legacyMaterials(logger)) {
            try {
                byId.putIfAbsent(material.getId(), material);
            } catch (RuntimeException ignored) {
                // у отдельных legacy-материалов id не спросить — пропускаем
            }
        }

        int mapped = 0;
        String firstError = null;
        for (int id = 1; id < MAX_ID; id++) {
            Material legacy = byId.get(id);
            if (legacy == null) {
                continue;
            }
            for (int data = 0; data < MAX_DATA; data++) {
                try {
                    Material modern = Bukkit.getUnsafe().fromLegacy(new MaterialData(legacy, (byte) data));
                    if (modern != null && !modern.isAir()) {
                        table[id][data] = modern;
                        mapped++;
                    }
                } catch (RuntimeException e) {
                    if (firstError == null) {
                        firstError = "id=" + id + " data=" + data + ": " + e;
                    }
                }
            }
        }

        logger.info("Таблица старых блоков: id " + byId.size() + ", сочетаний id+data " + mapped + ".");
        if (mapped == 0) {
            logger.warning("Конвертация старых блоков не работает — .schematic читаться не будет."
                    + (firstError == null ? "" : " Причина: " + firstError));
        }
    }

    /**
     * Собирает legacy-материалы.
     * <p>
     * Важно: в Paper 26 {@code Material.values()} их НЕ содержит (там только современные),
     * хотя сами константы существуют и {@code fromLegacy} с ними работает. Поэтому идём
     * рефлексией по полям класса. Если вдруг не выйдет — падаем обратно на values().
     */
    private static List<Material> legacyMaterials(Logger logger) {
        List<Material> result = new ArrayList<>();
        for (Field field : Material.class.getFields()) {
            if (!field.getName().startsWith("LEGACY_")
                    || !Modifier.isStatic(field.getModifiers())
                    || !Material.class.isAssignableFrom(field.getType())) {
                continue;
            }
            try {
                if (field.get(null) instanceof Material material && material.isLegacy()) {
                    result.add(material);
                }
            } catch (ReflectiveOperationException | RuntimeException ignored) {
                // поле недоступно — пропускаем
            }
        }
        if (result.isEmpty()) {
            for (Material material : Material.values()) {
                if (material.isLegacy()) {
                    result.add(material);
                }
            }
            if (result.isEmpty()) {
                logger.warning("Legacy-материалы не найдены — старые схематики читаться не будут.");
            }
        }
        return result;
    }

    /** Современный материал по старым id и data, либо null. */
    public Material toModern(int id, int data) {
        if (id <= 0 || id >= MAX_ID || data < 0 || data >= MAX_DATA) {
            return null;
        }
        return table[id][data];
    }
}
