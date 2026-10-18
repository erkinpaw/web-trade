package org.webtrade.webTrade.market;

import org.bukkit.Material;
import org.webtrade.webTrade.resource.ResourceRegistry;
import org.webtrade.webTrade.resource.ResourceType;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Разбор списка ресурсов, набранного текстом. Именно так контейнер и набирают:
 * калькулятор снаружи выдаёт готовый список, игрок вставляет его одним куском.
 * <p>
 * Формат нарочно нестрогий — лишь бы человек не воевал с синтаксисом:
 * <pre>
 *   STONE 3000
 *   minecraft:oak_planks 500
 *   glass x128
 *   12s spruce_planks      (s — стаки, 12 стаков)
 * </pre>
 */
public final class OrderList {

    /**
     * Столько строк разбираем и не больше — защита от вставки чего попало.
     * С запасом на весь каталог: торгуемых ресурсов около тысячи.
     */
    private static final int MAX_LINES = 1200;
    private static final int STACK = ResourceType.STACK;

    /** Число с необязательным «x» спереди и необязательной пометкой стаков сзади. */
    private static final Pattern AMOUNT = Pattern.compile(
            "^[x×*]?(\\d{1,7})(s|st|ст|стак|стаков)?$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    /**
     * Пространство имён убираем до разбора: двоеточие у нас разделитель, иначе
     * «minecraft:oak_planks» распалось бы на два куска и название не нашлось бы.
     */
    private static final Pattern NAMESPACE = Pattern.compile("(?i)\\bminecraft:");

    private OrderList() {
    }

    /**
     * @param lines    что удалось разобрать, количества сложены по совпадающим ресурсам
     * @param problems строки, которые разобрать не вышло, — покажем игроку как есть
     */
    public record Result(Map<Material, Integer> lines, List<String> problems) {

        public boolean isEmpty() {
            return lines.isEmpty();
        }
    }

    public static Result parse(String text, ResourceRegistry resources) {
        Map<Material, Integer> parsed = new LinkedHashMap<>();
        List<String> problems = new ArrayList<>();
        if (text == null || text.isBlank()) {
            return new Result(parsed, problems);
        }

        int seen = 0;
        for (String raw : text.split("\\R|;")) {
            String line = raw.trim();
            if (line.isEmpty() || line.startsWith("#") || line.startsWith("//")) {
                continue;
            }
            if (++seen > MAX_LINES) {
                problems.add("…дальше не читал: больше " + MAX_LINES + " строк");
                break;
            }
            if (!parseLine(line, resources, parsed)) {
                problems.add(line);
            }
        }
        return new Result(parsed, problems);
    }

    private static boolean parseLine(String line, ResourceRegistry resources,
                                     Map<Material, Integer> into) {
        String[] tokens = NAMESPACE.matcher(line).replaceAll("").split("[\\s,:=]+");
        if (tokens.length < 2) {
            return false;
        }

        int amount = -1;
        List<String> nameParts = new ArrayList<>();
        for (String token : tokens) {
            if (token.isEmpty()) {
                continue;
            }
            // Число может стоять и до названия, и после — берём первое подходящее.
            Matcher matcher = amount < 0 ? AMOUNT.matcher(token) : null;
            if (matcher != null && matcher.matches()) {
                amount = Integer.parseInt(matcher.group(1));
                if (matcher.group(2) != null) {
                    amount *= STACK;
                }
            } else {
                nameParts.add(token);
            }
        }
        if (amount <= 0 || nameParts.isEmpty()) {
            return false;
        }

        Material material = matchMaterial(nameParts);
        if (material == null || !resources.isTradeable(material)) {
            return false;
        }
        into.merge(material, amount, Integer::sum);
        return true;
    }

    /** Пробуем и как есть, и склеив слова подчёркиванием: «oak planks» — тоже понятно. */
    private static Material matchMaterial(List<String> nameParts) {
        String joined = String.join("_", nameParts).toLowerCase(Locale.ROOT);
        Material material = Material.matchMaterial(joined);
        if (material == null && nameParts.size() == 1) {
            material = Material.matchMaterial(nameParts.get(0));
        }
        return material;
    }
}
