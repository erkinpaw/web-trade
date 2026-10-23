package org.webtrade.webTrade.assets;

import com.google.gson.Gson;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import java.awt.image.BufferedImage;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;

import javax.imageio.ImageIO;

/**
 * Рендерит инвентарный значок блока из его модели — так же, как это делает клиент.
 * <p>
 * Модель — это набор коробок (elements) с текстурами на гранях. Клиент показывает их
 * под углом из display.gui (обычно поворот 30°/225°, масштаб 0.625). Здесь то же самое:
 * ортографическая проекция + z-буфер + ваниллное затенение граней.
 * <p>
 * От Bukkit не зависит — читает всё из клиентского jar.
 */
public final class ModelRenderer {

    /** Яркость граней — как в ванилле. */
    private static final double SHADE_UP = 1.0;
    private static final double SHADE_DOWN = 0.5;
    private static final double SHADE_NORTH_SOUTH = 0.8;
    private static final double SHADE_EAST_WEST = 0.6;

    /** Приблизительный цвет травы/листвы для граней с tintindex. */
    private static final int TINT = 0x7CBD6B;

    private static final String[] FACES = {"down", "up", "north", "south", "west", "east"};

    private final ZipFile zip;
    private final Gson gson = new Gson();
    private final Map<String, Model> modelCache = new HashMap<>();
    private final Map<String, BufferedImage> textureCache = new HashMap<>();

    public ModelRenderer(ZipFile zip) {
        this.zip = zip;
    }

    // ---------- модель ----------

    private static final class Model {
        Map<String, String> textures = new HashMap<>();
        List<Element> elements = new ArrayList<>();
        double[] guiRotation;
        double guiScale = 0.625;
    }

    private static final class Element {
        double[] from;
        double[] to;
        Map<String, Face> faces = new HashMap<>();
        double[] rotOrigin;
        String rotAxis;
        double rotAngle;
    }

    private static final class Face {
        String texture;
        double[] uv;      // может быть null — тогда считаем по умолчанию
        int rotation;
        boolean tinted;
    }

    /** Загружает модель со всеми родителями: текстуры сливаются, elements/display берутся ближайшие. */
    private Model resolve(String ref, int depth) {
        if (depth > 8 || ref == null) {
            return null;
        }
        String path = "assets/minecraft/models/" + stripNamespace(ref) + ".json";
        Model cached = modelCache.get(path);
        if (cached != null) {
            return cached;
        }
        JsonObject json = readJson(path);
        if (json == null) {
            return null;
        }

        Model model = new Model();
        Model parent = json.has("parent") ? resolve(json.get("parent").getAsString(), depth + 1) : null;
        if (parent != null) {
            model.textures.putAll(parent.textures);
            model.elements = parent.elements;
            model.guiRotation = parent.guiRotation;
            model.guiScale = parent.guiScale;
        }
        if (json.has("textures")) {
            for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("textures").entrySet()) {
                model.textures.put(entry.getKey(), entry.getValue().getAsString());
            }
        }
        if (json.has("elements")) {
            model.elements = parseElements(json.getAsJsonArray("elements"));
        }
        if (json.has("display")) {
            JsonObject display = json.getAsJsonObject("display");
            if (display.has("gui")) {
                JsonObject gui = display.getAsJsonObject("gui");
                if (gui.has("rotation")) {
                    model.guiRotation = doubles(gui, "rotation");
                }
                if (gui.has("scale")) {
                    model.guiScale = doubles(gui, "scale")[0];
                }
            }
        }
        modelCache.put(path, model);
        return model;
    }

    private List<Element> parseElements(com.google.gson.JsonArray array) {
        List<Element> elements = new ArrayList<>();
        for (JsonElement raw : array) {
            JsonObject json = raw.getAsJsonObject();
            Element element = new Element();
            element.from = doubles(json, "from");
            element.to = doubles(json, "to");
            if (element.from == null || element.to == null) {
                continue;
            }
            if (json.has("rotation")) {
                JsonObject rotation = json.getAsJsonObject("rotation");
                element.rotOrigin = doubles(rotation, "origin");
                element.rotAxis = rotation.has("axis") ? rotation.get("axis").getAsString() : null;
                element.rotAngle = rotation.has("angle") ? rotation.get("angle").getAsDouble() : 0;
            }
            if (json.has("faces")) {
                for (Map.Entry<String, JsonElement> entry : json.getAsJsonObject("faces").entrySet()) {
                    JsonObject faceJson = entry.getValue().getAsJsonObject();
                    Face face = new Face();
                    face.texture = faceJson.has("texture") ? faceJson.get("texture").getAsString() : null;
                    face.uv = doubles(faceJson, "uv");
                    face.rotation = faceJson.has("rotation") ? faceJson.get("rotation").getAsInt() : 0;
                    face.tinted = faceJson.has("tintindex");
                    element.faces.put(entry.getKey(), face);
                }
            }
            elements.add(element);
        }
        return elements;
    }

    // ---------- рендер ----------

    /** Рисует значок size×size. null — если модель не найдена или пуста. */
    public BufferedImage render(String modelRef, int size) {
        Model model = resolve(modelRef, 0);
        if (model == null) {
            return null;
        }
        // плоский предмет (item/generated) — просто отдаём текстуру
        if (model.elements.isEmpty()) {
            String flat = resolveTexture(model, "#layer0");
            return flat == null ? null : loadTexture(flat);
        }

        double[] rot = model.guiRotation != null ? model.guiRotation : new double[]{30, 225, 0};
        double scale = model.guiScale;

        BufferedImage image = new BufferedImage(size, size, BufferedImage.TYPE_INT_ARGB);
        double[] depth = new double[size * size];
        java.util.Arrays.fill(depth, Double.NEGATIVE_INFINITY);

        for (Element element : model.elements) {
            for (String faceName : FACES) {
                Face face = element.faces.get(faceName);
                if (face == null) {
                    continue;
                }
                String texturePath = resolveTexture(model, face.texture);
                if (texturePath == null) {
                    continue;
                }
                BufferedImage texture = loadTexture(texturePath);
                if (texture == null) {
                    continue;
                }
                drawFace(image, depth, size, element, faceName, face, texture, rot, scale);
            }
        }
        return image;
    }

    /** Рисует одну грань: прямоугольник в модели — параллелограмм на экране, поэтому обратное отображение точное. */
    private void drawFace(BufferedImage image, double[] depth, int size, Element element,
                          String faceName, Face face, BufferedImage texture,
                          double[] rot, double scale) {
        // три опорные точки грани: P(0,0), P(1,0), P(0,1)
        double[] p00 = facePoint(element, faceName, 0, 0);
        double[] p10 = facePoint(element, faceName, 1, 0);
        double[] p01 = facePoint(element, faceName, 0, 1);

        double[] s00 = project(rotateElement(element, p00), rot, scale, size);
        double[] s10 = project(rotateElement(element, p10), rot, scale, size);
        double[] s01 = project(rotateElement(element, p01), rot, scale, size);

        double e1x = s10[0] - s00[0], e1y = s10[1] - s00[1], e1z = s10[2] - s00[2];
        double e2x = s01[0] - s00[0], e2y = s01[1] - s00[1], e2z = s01[2] - s00[2];

        double det = e1x * e2y - e2x * e1y;
        if (Math.abs(det) < 1e-9) {
            return; // грань смотрит ровно вбок — не видна
        }

        double[] uv = face.uv != null ? face.uv : defaultUv(element, faceName);
        double shade = shade(faceName);

        int minX = (int) Math.floor(Math.min(Math.min(s00[0], s10[0]), Math.min(s01[0], s10[0] + e2x)));
        int maxX = (int) Math.ceil(Math.max(Math.max(s00[0], s10[0]), Math.max(s01[0], s10[0] + e2x)));
        int minY = (int) Math.floor(Math.min(Math.min(s00[1], s10[1]), Math.min(s01[1], s10[1] + e2y)));
        int maxY = (int) Math.ceil(Math.max(Math.max(s00[1], s10[1]), Math.max(s01[1], s10[1] + e2y)));
        minX = Math.max(0, minX);
        minY = Math.max(0, minY);
        maxX = Math.min(size - 1, maxX);
        maxY = Math.min(size - 1, maxY);

        for (int y = minY; y <= maxY; y++) {
            for (int x = minX; x <= maxX; x++) {
                // центр пикселя
                double px = x + 0.5 - s00[0];
                double py = y + 0.5 - s00[1];
                double s = (px * e2y - e2x * py) / det;
                double t = (e1x * py - px * e1y) / det;
                if (s < 0 || s > 1 || t < 0 || t > 1) {
                    continue;
                }
                double z = s00[2] + s * e1z + t * e2z;
                int index = y * size + x;
                if (z <= depth[index]) {
                    continue;
                }
                int argb = sample(texture, uv, s, t, face.rotation);
                if ((argb >>> 24) < 16) {
                    continue; // прозрачно
                }
                depth[index] = z;
                image.setRGB(x, y, applyShade(argb, shade, face.tinted));
            }
        }
    }

    /** Точка грани по параметрам (s,t) в координатах модели 0..16. Формулы согласованы с ваниллной UV-развёрткой. */
    private static double[] facePoint(Element e, String face, double s, double t) {
        double x1 = e.from[0], y1 = e.from[1], z1 = e.from[2];
        double x2 = e.to[0], y2 = e.to[1], z2 = e.to[2];
        return switch (face) {
            case "up" -> new double[]{lerp(x1, x2, s), y2, lerp(z1, z2, t)};
            case "down" -> new double[]{lerp(x1, x2, s), y1, lerp(z2, z1, t)};
            case "north" -> new double[]{lerp(x2, x1, s), lerp(y2, y1, t), z1};
            case "south" -> new double[]{lerp(x1, x2, s), lerp(y2, y1, t), z2};
            case "west" -> new double[]{x1, lerp(y2, y1, t), lerp(z1, z2, s)};
            case "east" -> new double[]{x2, lerp(y2, y1, t), lerp(z2, z1, s)};
            default -> new double[]{0, 0, 0};
        };
    }

    /** UV по умолчанию, если в модели не задана явно (как в ванилле). */
    private static double[] defaultUv(Element e, String face) {
        double x1 = e.from[0], y1 = e.from[1], z1 = e.from[2];
        double x2 = e.to[0], y2 = e.to[1], z2 = e.to[2];
        return switch (face) {
            case "up" -> new double[]{x1, z1, x2, z2};
            case "down" -> new double[]{x1, 16 - z2, x2, 16 - z1};
            case "north" -> new double[]{16 - x2, 16 - y2, 16 - x1, 16 - y1};
            case "south" -> new double[]{x1, 16 - y2, x2, 16 - y1};
            case "west" -> new double[]{z1, 16 - y2, z2, 16 - y1};
            case "east" -> new double[]{16 - z2, 16 - y2, 16 - z1, 16 - y1};
            default -> new double[]{0, 0, 16, 16};
        };
    }

    private static double shade(String face) {
        return switch (face) {
            case "up" -> SHADE_UP;
            case "down" -> SHADE_DOWN;
            case "north", "south" -> SHADE_NORTH_SOUTH;
            default -> SHADE_EAST_WEST;
        };
    }

    /** Поворот элемента вокруг своей оси (нужен для факелов, рычагов и подобного). */
    private static double[] rotateElement(Element e, double[] p) {
        if (e.rotAxis == null || e.rotAngle == 0 || e.rotOrigin == null) {
            return p;
        }
        double a = Math.toRadians(e.rotAngle);
        double cos = Math.cos(a), sin = Math.sin(a);
        double x = p[0] - e.rotOrigin[0], y = p[1] - e.rotOrigin[1], z = p[2] - e.rotOrigin[2];
        double nx = x, ny = y, nz = z;
        switch (e.rotAxis) {
            case "x" -> { ny = y * cos - z * sin; nz = y * sin + z * cos; }
            case "y" -> { nx = x * cos + z * sin; nz = -x * sin + z * cos; }
            case "z" -> { nx = x * cos - y * sin; ny = x * sin + y * cos; }
            default -> { }
        }
        return new double[]{nx + e.rotOrigin[0], ny + e.rotOrigin[1], nz + e.rotOrigin[2]};
    }

    /**
     * Модель (0..16) -> экран. Поворот как в ванилле: rotationXYZ, т.е. сперва Z, потом Y, потом X.
     * Проекция ортографическая, глубина — координата z (больше = ближе).
     */
    private static double[] project(double[] p, double[] rot, double scale, int size) {
        double x = (p[0] - 8) / 16.0;
        double y = (p[1] - 8) / 16.0;
        double z = (p[2] - 8) / 16.0;

        double rz = Math.toRadians(rot[2]);
        double nx = x * Math.cos(rz) - y * Math.sin(rz);
        double ny = x * Math.sin(rz) + y * Math.cos(rz);
        x = nx; y = ny;

        double ry = Math.toRadians(rot[1]);
        nx = x * Math.cos(ry) + z * Math.sin(ry);
        double nz = -x * Math.sin(ry) + z * Math.cos(ry);
        x = nx; z = nz;

        double rx = Math.toRadians(rot[0]);
        ny = y * Math.cos(rx) - z * Math.sin(rx);
        nz = y * Math.sin(rx) + z * Math.cos(rx);
        y = ny; z = nz;

        x *= scale; y *= scale; z *= scale;
        return new double[]{size / 2.0 + x * size, size / 2.0 - y * size, z};
    }

    // ---------- текстуры ----------

    private int sample(BufferedImage texture, double[] uv, double s, double t, int rotation) {
        double rs = s, rt = t;
        switch (((rotation % 360) + 360) % 360) {
            case 90 -> { rs = t; rt = 1 - s; }
            case 180 -> { rs = 1 - s; rt = 1 - t; }
            case 270 -> { rs = 1 - t; rt = s; }
            default -> { }
        }
        double u = lerp(uv[0], uv[2], rs);
        double v = lerp(uv[1], uv[3], rt);
        int tx = (int) (u / 16.0 * texture.getWidth());
        int ty = (int) (v / 16.0 * texture.getHeight());
        tx = Math.max(0, Math.min(texture.getWidth() - 1, tx));
        ty = Math.max(0, Math.min(texture.getHeight() - 1, ty));
        return texture.getRGB(tx, ty);
    }

    private static int applyShade(int argb, double shade, boolean tinted) {
        int a = argb >>> 24;
        int r = (argb >> 16) & 0xFF;
        int g = (argb >> 8) & 0xFF;
        int b = argb & 0xFF;
        if (tinted) {
            r = r * ((TINT >> 16) & 0xFF) / 255;
            g = g * ((TINT >> 8) & 0xFF) / 255;
            b = b * (TINT & 0xFF) / 255;
        }
        r = (int) Math.min(255, r * shade);
        g = (int) Math.min(255, g * shade);
        b = (int) Math.min(255, b * shade);
        return (a << 24) | (r << 16) | (g << 8) | b;
    }

    private BufferedImage loadTexture(String ref) {
        String path = "assets/minecraft/textures/" + stripNamespace(ref) + ".png";
        if (textureCache.containsKey(path)) {
            return textureCache.get(path);
        }
        BufferedImage image = null;
        ZipEntry entry = zip.getEntry(path);
        if (entry != null) {
            try (InputStream in = zip.getInputStream(entry)) {
                image = ImageIO.read(in);
                // анимированные текстуры — это вертикальная лента кадров, берём первый
                if (image != null && image.getHeight() > image.getWidth()
                        && image.getHeight() % image.getWidth() == 0) {
                    image = image.getSubimage(0, 0, image.getWidth(), image.getWidth());
                }
            } catch (Exception ignored) {
                image = null;
            }
        }
        textureCache.put(path, image);
        return image;
    }

    private String resolveTexture(Model model, String ref) {
        int guard = 0;
        while (ref != null && ref.startsWith("#") && guard++ < 10) {
            ref = model.textures.get(ref.substring(1));
        }
        return ref;
    }

    // ---------- мелочи ----------

    private JsonObject readJson(String path) {
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

    private static double[] doubles(JsonObject json, String key) {
        if (!json.has(key) || !json.get(key).isJsonArray()) {
            return null;
        }
        com.google.gson.JsonArray array = json.getAsJsonArray(key);
        double[] values = new double[array.size()];
        for (int i = 0; i < values.length; i++) {
            values[i] = array.get(i).getAsDouble();
        }
        return values;
    }

    private static double lerp(double a, double b, double f) {
        return a + (b - a) * f;
    }

    private static String stripNamespace(String ref) {
        int colon = ref.indexOf(':');
        return colon < 0 ? ref : ref.substring(colon + 1);
    }
}
