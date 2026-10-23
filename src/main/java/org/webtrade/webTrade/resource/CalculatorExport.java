package org.webtrade.webTrade.resource;

import org.bukkit.plugin.Plugin;
import org.webtrade.webTrade.assets.CalculatorAssets;
import org.webtrade.webTrade.schematic.LegacyBlocks;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.Locale;
import java.util.StringJoiner;

/**
 * Выгружает калькулятор контейнера — самодостаточную HTML-страницу с текущим каталогом
 * и ценами внутри.
 * <p>
 * Страница ничего никуда не запрашивает и с сервером не связана: игрок набирает список
 * снаружи и вставляет его в игре. Поэтому здесь нет ни входа, ни сессий — только цифры,
 * зашитые на момент выгрузки. Поменялись цены — выгрузите заново.
 */
public final class CalculatorExport {

    private static final String TEMPLATE = "calculator.html";
    private static final String CATALOG_PLACEHOLDER = "__CATALOG_JSON__";
    private static final String LEGACY_PLACEHOLDER = "__LEGACY_JSON__";
    private static final String STAMP_PLACEHOLDER = "__STAMP__";
    private static final String ICONS_PLACEHOLDER = "__ICONS_JSON__";

    private static final int MAX_ID = 256;
    private static final int MAX_DATA = 16;

    private CalculatorExport() {
    }

    /**
     * @return путь к записанному файлу
     */
    public static Path write(Plugin plugin, ResourceRegistry resources, LegacyBlocks legacy,
                             CalculatorAssets.Result assets) throws IOException {
        String template = readTemplate(plugin);
        String stamp = LocalDate.now().toString();
        String page = template
                .replace(CATALOG_PLACEHOLDER, catalogJson(resources, assets))
                .replace(LEGACY_PLACEHOLDER, legacyJson(legacy))
                .replace(ICONS_PLACEHOLDER, iconsJson(assets))
                .replace(STAMP_PLACEHOLDER, stamp);

        Path target = plugin.getDataFolder().toPath().resolve("calculator.html");
        Files.writeString(target, page, StandardCharsets.UTF_8);
        return target;
    }

    private static String readTemplate(Plugin plugin) throws IOException {
        try (InputStream stream = plugin.getResource(TEMPLATE)) {
            if (stream == null) {
                throw new IOException("В jar нет " + TEMPLATE);
            }
            return new String(stream.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    /** name — по-русски, если названия скачались; en — техническое, чтобы искать можно было и так. */
    private static String catalogJson(ResourceRegistry resources, CalculatorAssets.Result assets) {
        StringJoiner json = new StringJoiner(",\n", "[\n", "\n]");
        for (ResourceType type : resources.all()) {
            String id = type.material().name();
            String name = assets.names().getOrDefault(id, readable(id));
            Integer icon = assets.icons().get(id);
            json.add("{\"id\":\"" + id + "\",\"name\":\"" + escape(name) + "\",\"en\":\"" + readable(id)
                    + "\",\"price\":" + String.format(Locale.US, "%.4f", type.pricePerStack())
                    + (icon != null ? ",\"icon\":" + icon : "") + "}");
        }
        return json.toString();
    }

    /** Где лежит атлас значков; null — значков нет, страница рисует строки без них. */
    private static String iconsJson(CalculatorAssets.Result assets) {
        if (!assets.hasIcons()) {
            return "null";
        }
        return "{\"file\":\"" + CalculatorAssets.ATLAS_FILE + "?v=" + assets.icons().size()
                + "\",\"size\":" + assets.size() + ",\"cols\":" + assets.columns()
                + ",\"rows\":" + (assets.icons().size() + assets.columns() - 1) / assets.columns() + "}";
    }

    private static String escape(String text) {
        return text.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    /**
     * Таблица старых числовых ID для разбора {@code .schematic} прямо в браузере.
     * <p>
     * Собрать её может только сервер — она строится из Bukkit при старте. Формат сжатый:
     * если все 16 значений data ведут к одному материалу, пишем строку вместо объекта,
     * иначе объект «data → материал». Так таблица занимает вдвое меньше места.
     */
    private static String legacyJson(LegacyBlocks legacy) {
        StringJoiner json = new StringJoiner(",", "{", "}");
        for (int id = 1; id < MAX_ID; id++) {
            String[] byData = new String[MAX_DATA];
            boolean any = false;
            for (int data = 0; data < MAX_DATA; data++) {
                var material = legacy.toModern(id, data);
                if (material != null) {
                    byData[data] = material.name();
                    any = true;
                }
            }
            if (!any) {
                continue;
            }
            json.add("\"" + id + "\":" + (uniform(byData) ? "\"" + byData[0] + "\"" : dataMap(byData)));
        }
        return json.toString();
    }

    /** Все 16 значений data ведут к одному и тому же материалу. */
    private static boolean uniform(String[] byData) {
        if (byData[0] == null) {
            return false;
        }
        for (String name : byData) {
            if (!byData[0].equals(name)) {
                return false;
            }
        }
        return true;
    }

    private static String dataMap(String[] byData) {
        StringJoiner json = new StringJoiner(",", "{", "}");
        for (int data = 0; data < byData.length; data++) {
            if (byData[data] != null) {
                json.add("\"" + data + "\":\"" + byData[data] + "\"");
            }
        }
        return json.toString();
    }

    /** OAK_PLANKS → «Oak planks». Русские названия живут на клиенте, на сервере их нет. */
    private static String readable(String materialName) {
        String spaced = materialName.toLowerCase(Locale.ROOT).replace('_', ' ');
        return Character.toUpperCase(spaced.charAt(0)) + spaced.substring(1);
    }
}
