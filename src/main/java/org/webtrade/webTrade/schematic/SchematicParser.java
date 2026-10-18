package org.webtrade.webTrade.schematic;

import org.bukkit.Material;
import org.webtrade.webTrade.resource.ResourceRegistry;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Разбирает схематику формата MCEdit (.schematic) и считает, сколько каких блоков нужно.
 * <p>
 * Формат: gzip + NBT, блоки лежат старыми числовыми ID (Blocks + Data, при больших ID ещё AddBlocks).
 */
public final class SchematicParser {

    /** Что получилось из схематики. */
    public record Result(
            int width, int height, int length,
            int totalBlocks,
            /** Торгуемые блоки: материал -> количество, по убыванию. */
            LinkedHashMap<Material, Integer> counts,
            /** Блоки, которых нет в каталоге (их не заказать): материал -> количество. */
            LinkedHashMap<Material, Integer> skipped,
            /** Блоки, которые вообще не удалось опознать. */
            int unknown) {
    }

    private final ResourceRegistry registry;
    private final LegacyBlocks legacy;

    public SchematicParser(ResourceRegistry registry, LegacyBlocks legacy) {
        this.registry = registry;
        this.legacy = legacy;
    }

    public Result parse(byte[] data) throws IOException {
        Map<String, Object> root = NbtReader.read(new ByteArrayInputStream(data));

        byte[] blocks = bytes(root.get("Blocks"));
        if (blocks == null) {
            throw new IOException("В файле нет блоков — это не .schematic формата MCEdit "
                    + "(новый .schem и .litematica пока не поддерживаются).");
        }
        byte[] blockData = bytes(root.get("Data"));
        byte[] addBlocks = bytes(root.get("AddBlocks"));

        int width = shortValue(root.get("Width"));
        int height = shortValue(root.get("Height"));
        int length = shortValue(root.get("Length"));

        Map<Material, Integer> counts = new HashMap<>();
        Map<Material, Integer> skipped = new HashMap<>();
        int unknown = 0;
        int total = 0;

        for (int i = 0; i < blocks.length; i++) {
            int id = blocks[i] & 0xFF;
            // AddBlocks хранит старшие 4 бита ID по половинке байта на блок
            if (addBlocks != null && (i >> 1) < addBlocks.length) {
                int add = (i & 1) == 0
                        ? (addBlocks[i >> 1] & 0x0F) << 8
                        : (addBlocks[i >> 1] & 0xF0) << 4;
                id |= add;
            }
            if (id == 0) {
                continue; // воздух
            }
            int dv = (blockData != null && i < blockData.length) ? (blockData[i] & 0x0F) : 0;

            Material material = legacy.toModern(id, dv);
            if (material == null) {
                unknown++;
                continue;
            }
            total++;
            if (registry.isTradeable(material)) {
                counts.merge(material, 1, Integer::sum);
            } else {
                skipped.merge(material, 1, Integer::sum);
            }
        }

        return new Result(width, height, length, total,
                sortDesc(counts), sortDesc(skipped), unknown);
    }

    private static LinkedHashMap<Material, Integer> sortDesc(Map<Material, Integer> map) {
        LinkedHashMap<Material, Integer> sorted = new LinkedHashMap<>();
        map.entrySet().stream()
                .sorted(Map.Entry.<Material, Integer>comparingByValue().reversed())
                .forEach(entry -> sorted.put(entry.getKey(), entry.getValue()));
        return sorted;
    }

    private static byte[] bytes(Object value) {
        return value instanceof byte[] array ? array : null;
    }

    private static int shortValue(Object value) {
        return value instanceof Short number ? number & 0xFFFF : 0;
    }
}
