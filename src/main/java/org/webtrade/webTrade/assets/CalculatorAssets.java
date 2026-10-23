package org.webtrade.webTrade.assets;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import javax.imageio.ImageIO;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.logging.Logger;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

/**
 * Русские названия и значки предметов для калькулятора.
 * <p>
 * Берутся с официальных серверов Mojang: {@code ru_ru.json} из ассетов и текстуры/модели
 * из клиентского jar той же версии, что и сервер. Значки складываются в один атлас
 * {@code calculator-icons.png} рядом с {@code calculator.html} — страница грузит одну
 * картинку, а не тысячу. Скачивание разовое, дальше всё берётся из кэша {@code assets/}.
 * Сами ассеты Mojang в репозитории не хранятся. Нет сети — калькулятор работает как раньше,
 * с техническими именами и без значков.
 * <p>
 * От Bukkit не зависит: на вход — список ID материалов, на выход — файлы и {@link Result}.
 */
public final class CalculatorAssets {

    public static final String ATLAS_FILE = "calculator-icons.png";

    private static final String MANIFEST_URL = "https://piston-meta.mojang.com/mc/game/version_manifest_v2.json";
    private static final String RESOURCES_URL = "https://resources.download.minecraft.net/";
    private static final String LANG_OBJECT = "minecraft/lang/ru_ru.json";
    private static final String[] TEXTURE_KEYS = {"layer0", "all", "texture", "side", "front", "top", "particle"};

    /** Значок рисуется крупно, а на странице показывается вдвое меньше — чётко и на экранах с высокой плотностью. */
    private static final int ICON_SIZE = 64;
    private static final int COLUMNS = 32;
    /** Поднимайте, если поменялся способ рисования — старый кэш тогда перерисуется. */
    private static final String ASSET_VERSION = "4";

    /**
     * Что получилось: имена по ID материала, номер значка в атласе и ширина символов шрифта
     * игры в пикселях (код символа → ширина с пробелом после) — по ней стенды выравнивают колонки.
     */
    public record Result(Map<String, String> names, Map<String, Integer> icons, Map<Integer, Integer> glyphs,
                         int size, int columns) {
        public static Result empty() {
            return new Result(Map.of(), Map.of(), Map.of(), ICON_SIZE, COLUMNS);
        }

        public boolean hasIcons() {
            return !icons.isEmpty();
        }
    }

    private final Path dataFolder;
    private final Path cacheDir;
    private final Logger logger;
    private final Gson gson = new Gson();

    public CalculatorAssets(Path dataFolder, Logger logger) {
        this.dataFolder = dataFolder;
        this.cacheDir = dataFolder.resolve("assets");
        this.logger = logger;
    }

    /** Кэш от прошлых запусков, если он для этой версии игры и этого набора ресурсов. Иначе пусто. */
    public Result cached(String minecraftVersion, List<String> ids) {
        try {
            Path index = cacheDir.resolve("index.json");
            if (!Files.isReadable(index) || !Files.isReadable(dataFolder.resolve(ATLAS_FILE))) {
                return Result.empty();
            }
            JsonObject json = gson.fromJson(Files.readString(index, StandardCharsets.UTF_8), JsonObject.class);
            if (!stamp(minecraftVersion).equals(json.get("stamp").getAsString())) {
                return Result.empty();
            }
            Map<String, String> names = new LinkedHashMap<>();
            json.getAsJsonObject("names").entrySet().forEach(e -> names.put(e.getKey(), e.getValue().getAsString()));
            Map<String, Integer> icons = new LinkedHashMap<>();
            json.getAsJsonObject("icons").entrySet().forEach(e -> icons.put(e.getKey(), e.getValue().getAsInt()));
            Map<Integer, Integer> glyphs = new java.util.HashMap<>();
            json.getAsJsonObject("glyphs").entrySet().forEach(e ->
                    glyphs.put(Integer.parseInt(e.getKey()), e.getValue().getAsInt()));
            // в каталоге появились ресурсы, которых атлас ещё не видел — перерисуем
            java.util.Set<String> known = new java.util.HashSet<>(icons.keySet());
            json.getAsJsonArray("missing").forEach(e -> known.add(e.getAsString()));
            if (!known.containsAll(ids)) {
                return Result.empty();
            }
            return new Result(names, icons, glyphs, ICON_SIZE, COLUMNS);
        } catch (Exception e) {
            return Result.empty();
        }
    }

    /**
     * Скачивает и рисует. Вызывать не в главном потоке: сеть и ~40 МБ клиента.
     * При любой ошибке возвращает пустой результат — калькулятор просто останется без значков.
     */
    public Result prepare(String minecraftVersion, List<String> ids) {
        try {
            Files.createDirectories(cacheDir);
            logger.info("Калькулятор: качаю русские названия и рисую значки для " + minecraftVersion + " с серверов Mojang…");
            HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                    .followRedirects(HttpClient.Redirect.NORMAL).build();
            JsonObject version = versionJson(http, minecraftVersion);
            Map<String, String> names = names(http, version, ids);
            Map<String, Integer> icons = new LinkedHashMap<>();
            Map<Integer, Integer> glyphs = new java.util.HashMap<>();
            List<String> missingIcons = icons(http, version, ids, icons, glyphs);

            JsonObject index = new JsonObject();
            index.addProperty("stamp", stamp(minecraftVersion));
            index.add("names", gson.toJsonTree(names));
            index.add("icons", gson.toJsonTree(icons));
            index.add("glyphs", gson.toJsonTree(glyphs));
            index.add("missing", gson.toJsonTree(missingIcons));
            Files.writeString(cacheDir.resolve("index.json"), gson.toJson(index), StandardCharsets.UTF_8);
            logger.info("Калькулятор: названий " + names.size() + ", значков " + icons.size()
                    + (missingIcons.isEmpty() ? "." : ", без значка " + missingIcons.size() + ": " + missingIcons));
            return new Result(names, icons, glyphs, ICON_SIZE, COLUMNS);
        } catch (Exception e) {
            logger.warning("Калькулятор останется без значков и русских названий: " + e.getMessage());
            return Result.empty();
        }
    }

    private static String stamp(String minecraftVersion) {
        return minecraftVersion + "/" + ASSET_VERSION;
    }

    private JsonObject versionJson(HttpClient http, String minecraftVersion) throws Exception {
        for (JsonElement element : getJson(http, MANIFEST_URL).getAsJsonArray("versions")) {
            JsonObject version = element.getAsJsonObject();
            if (minecraftVersion.equals(version.get("id").getAsString())) {
                return getJson(http, version.get("url").getAsString());
            }
        }
        throw new IllegalStateException("версии " + minecraftVersion + " нет в манифесте Mojang");
    }

    // ---------- названия ----------

    private Map<String, String> names(HttpClient http, JsonObject version, List<String> ids) throws Exception {
        JsonObject objects = getJson(http, version.getAsJsonObject("assetIndex").get("url").getAsString())
                .getAsJsonObject("objects");
        Map<String, String> names = new LinkedHashMap<>();
        if (!objects.has(LANG_OBJECT)) {
            return names;
        }
        String hash = objects.getAsJsonObject(LANG_OBJECT).get("hash").getAsString();
        JsonObject lang = getJson(http, RESOURCES_URL + hash.substring(0, 2) + "/" + hash);
        for (String id : ids) {
            String key = id.toLowerCase(Locale.ROOT);
            for (String prefix : new String[]{"block.minecraft.", "item.minecraft."}) {
                if (lang.has(prefix + key)) {
                    names.put(id, lang.get(prefix + key).getAsString());
                    break;
                }
            }
        }
        return names;
    }

    // ---------- значки ----------

    /** Рисует атлас; возвращает ID, для которых значок не получился. */
    private List<String> icons(HttpClient http, JsonObject version, List<String> ids, Map<String, Integer> out,
                               Map<Integer, Integer> glyphs) throws Exception {
        String clientUrl = version.getAsJsonObject("downloads").getAsJsonObject("client").get("url").getAsString();
        Path jar = Files.createTempFile("mc-client", ".jar");
        List<String> missing = new java.util.ArrayList<>();
        try {
            HttpResponse<Path> response = http.send(HttpRequest.newBuilder(URI.create(clientUrl)).GET().build(),
                    HttpResponse.BodyHandlers.ofFile(jar));
            if (response.statusCode() != 200) {
                throw new IOException("client.jar: HTTP " + response.statusCode());
            }
            int rows = (ids.size() + COLUMNS - 1) / COLUMNS;
            BufferedImage atlas = new BufferedImage(COLUMNS * ICON_SIZE, Math.max(1, rows) * ICON_SIZE,
                    BufferedImage.TYPE_INT_ARGB);
            Graphics2D g = atlas.createGraphics();
            // плоские 16×16 текстуры увеличиваем без размытия — как в игре
            g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);
            try (ZipFile zip = new ZipFile(jar.toFile())) {
                glyphs.putAll(glyphWidths(zip));
                ModelRenderer renderer = new ModelRenderer(zip);
                int slot = 0;
                for (String id : ids) {
                    BufferedImage icon = icon(zip, renderer, id.toLowerCase(Locale.ROOT));
                    if (icon == null) {
                        missing.add(id);
                        continue;
                    }
                    g.drawImage(icon, (slot % COLUMNS) * ICON_SIZE, (slot / COLUMNS) * ICON_SIZE,
                            ICON_SIZE, ICON_SIZE, null);
                    out.put(id, slot++);
                }
            }
            g.dispose();
            // строк ровно столько, сколько значков получилось: страница считает позиции от этого
            int used = Math.max(1, (out.size() + COLUMNS - 1) / COLUMNS);
            ImageIO.write(atlas.getSubimage(0, 0, atlas.getWidth(), used * ICON_SIZE), "png",
                    dataFolder.resolve(ATLAS_FILE).toFile());
        } finally {
            Files.deleteIfExists(jar);
        }
        return missing;
    }

    /**
     * Ширина символов обычного шрифта игры, как её считает клиент: пробелы — из своего
     * провайдера, буквы — по картинке шрифта (последний непустой столбец глифа, масштаб
     * под высоту шрифта, плюс пиксель между буквами). Кто раньше в списке, тот и главный.
     */
    private Map<Integer, Integer> glyphWidths(ZipFile zip) throws IOException {
        Map<Integer, Integer> widths = new java.util.HashMap<>();
        JsonObject space = readJson(zip, "assets/minecraft/font/include/space.json");
        if (space != null) {
            for (JsonElement provider : space.getAsJsonArray("providers")) {
                JsonObject advances = provider.getAsJsonObject().getAsJsonObject("advances");
                if (advances != null) {
                    advances.entrySet().forEach(e -> widths.putIfAbsent(e.getKey().codePointAt(0),
                            (int) Math.round(e.getValue().getAsDouble())));
                }
            }
        }
        JsonObject font = readJson(zip, "assets/minecraft/font/include/default.json");
        if (font == null) {
            return widths;
        }
        for (JsonElement element : font.getAsJsonArray("providers")) {
            JsonObject provider = element.getAsJsonObject();
            if (!"bitmap".equals(provider.get("type").getAsString())) {
                continue;
            }
            BufferedImage sheet = read(zip, "assets/minecraft/textures/" + strip(provider.get("file").getAsString()));
            if (sheet == null) {
                continue;
            }
            List<String> rows = new java.util.ArrayList<>();
            provider.getAsJsonArray("chars").forEach(row -> rows.add(row.getAsString()));
            int columns = rows.getFirst().codePointCount(0, rows.getFirst().length());
            int cellW = sheet.getWidth() / columns, cellH = sheet.getHeight() / rows.size();
            double scale = (provider.has("height") ? provider.get("height").getAsInt() : 8) / (double) cellH;
            for (int r = 0; r < rows.size(); r++) {
                int[] points = rows.get(r).codePoints().toArray();
                for (int c = 0; c < points.length; c++) {
                    if (points[c] == 0 || widths.containsKey(points[c])) {
                        continue;
                    }
                    int last = -1;
                    for (int x = cellW - 1; x >= 0 && last < 0; x--) {
                        for (int y = 0; y < cellH; y++) {
                            if ((sheet.getRGB(c * cellW + x, r * cellH + y) >>> 24) != 0) {
                                last = x;
                                break;
                            }
                        }
                    }
                    widths.put(points[c], (int) (0.5 + (last + 1) * scale) + 1);
                }
            }
        }
        return widths;
    }

    /** Значок как в инвентаре: плоская текстура предмета, иначе модель блока, иначе хоть какая-то текстура. */
    private BufferedImage icon(ZipFile zip, ModelRenderer renderer, String name) throws IOException {
        String modelRef = itemModelRef(zip, name);
        // 1) предмет, который в инвентаре плоский (item/generated): его текстура и есть значок
        String flat = flatTexture(zip, modelRef, 0);
        if (flat != null) {
            BufferedImage image = read(zip, flat);
            if (image != null) {
                return image.getHeight() > image.getWidth() ? crop(image) : image;
            }
        }
        // 2) блок — рисуем модель, как клиент
        try {
            BufferedImage rendered = renderer.render(modelRef != null ? modelRef : "minecraft:block/" + name, ICON_SIZE);
            if (!isBlank(rendered)) {
                return rendered;
            }
        } catch (RuntimeException ignored) {
            // кривая модель — уходим в запасной путь
        }
        // 3) сундуки, таблички и прочее со своим рендером: хоть плоская текстура
        String texture = anyTexture(zip, name, modelRef);
        return texture == null ? null : read(zip, texture);
    }

    /** Анимированная текстура — полоска кадров; берём первый. */
    private static BufferedImage crop(BufferedImage image) {
        return image.getSubimage(0, 0, image.getWidth(), image.getWidth());
    }

    /** Текстура layer0, если модель в итоге наследует item/generated (или handheld). */
    private String flatTexture(ZipFile zip, String modelRef, int depth) {
        if (modelRef == null || depth > 8) {
            return null;
        }
        String path = strip(modelRef);
        if (path.equals("item/generated") || path.equals("item/handheld") || path.equals("builtin/generated")) {
            return "";
        }
        JsonObject model = readJson(zip, "assets/minecraft/models/" + path + ".json");
        if (model == null || !model.has("parent")) {
            return null;
        }
        String parent = flatTexture(zip, model.get("parent").getAsString(), depth + 1);
        if (parent == null) {
            return null;
        }
        if (!parent.isEmpty()) {
            return parent;
        }
        String layer = model.has("textures") ? texturePath(model.getAsJsonObject("textures"), "layer0") : null;
        return layer != null ? layer : "";
    }

    private String anyTexture(ZipFile zip, String name, String modelRef) {
        for (String direct : new String[]{"assets/minecraft/textures/item/" + name + ".png",
                "assets/minecraft/textures/block/" + name + ".png"}) {
            if (zip.getEntry(direct) != null) {
                return direct;
            }
        }
        return textureFromModel(zip, modelRef != null ? modelRef : "minecraft:item/" + name, 0);
    }

    private String itemModelRef(ZipFile zip, String name) {
        JsonObject json = readJson(zip, "assets/minecraft/items/" + name + ".json");
        return json == null ? null : findModelRef(json, 0);
    }

    /** Первое строковое поле "model": определения предметов бывают обёрнуты в select/condition. */
    private String findModelRef(JsonElement element, int depth) {
        if (depth > 6 || element == null) {
            return null;
        }
        if (element.isJsonObject()) {
            JsonObject object = element.getAsJsonObject();
            JsonElement model = object.get("model");
            if (model != null && model.isJsonPrimitive()) {
                return model.getAsString();
            }
            for (Map.Entry<String, JsonElement> entry : object.entrySet()) {
                String found = findModelRef(entry.getValue(), depth + 1);
                if (found != null) {
                    return found;
                }
            }
        } else if (element.isJsonArray()) {
            for (JsonElement child : element.getAsJsonArray()) {
                String found = findModelRef(child, depth + 1);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    private String textureFromModel(ZipFile zip, String modelRef, int depth) {
        if (depth > 6 || modelRef == null) {
            return null;
        }
        JsonObject model = readJson(zip, "assets/minecraft/models/" + strip(modelRef) + ".json");
        if (model == null) {
            return null;
        }
        if (model.has("textures")) {
            JsonObject textures = model.getAsJsonObject("textures");
            for (String key : TEXTURE_KEYS) {
                String path = texturePath(textures, key);
                if (path != null) {
                    return path;
                }
            }
            for (String key : textures.keySet()) {
                String path = texturePath(textures, key);
                if (path != null) {
                    return path;
                }
            }
        }
        return model.has("parent") ? textureFromModel(zip, model.get("parent").getAsString(), depth + 1) : null;
    }

    private static String texturePath(JsonObject textures, String key) {
        if (!textures.has(key) || !textures.get(key).isJsonPrimitive()) {
            return null;
        }
        String value = textures.get(key).getAsString();
        return value.startsWith("#") ? null : "assets/minecraft/textures/" + strip(value) + ".png";
    }

    private static String strip(String ref) {
        int colon = ref.indexOf(':');
        return colon < 0 ? ref : ref.substring(colon + 1);
    }

    private static boolean isBlank(BufferedImage image) {
        if (image == null) {
            return true;
        }
        for (int y = 0; y < image.getHeight(); y++) {
            for (int x = 0; x < image.getWidth(); x++) {
                if ((image.getRGB(x, y) >>> 24) > 8) {
                    return false;
                }
            }
        }
        return true;
    }

    private static BufferedImage read(ZipFile zip, String path) throws IOException {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry)) {
            return ImageIO.read(in);
        }
    }

    private JsonObject readJson(ZipFile zip, String path) {
        ZipEntry entry = zip.getEntry(path);
        if (entry == null) {
            return null;
        }
        try (InputStream in = zip.getInputStream(entry);
             InputStreamReader reader = new InputStreamReader(in, StandardCharsets.UTF_8)) {
            return gson.fromJson(reader, JsonObject.class);
        } catch (Exception e) {
            return null;
        }
    }

    private JsonObject getJson(HttpClient http, String url) throws Exception {
        HttpResponse<String> response = http.send(HttpRequest.newBuilder(URI.create(url)).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        if (response.statusCode() != 200) {
            throw new IOException(url + ": HTTP " + response.statusCode());
        }
        return gson.fromJson(response.body(), JsonObject.class);
    }
}
