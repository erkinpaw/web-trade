package org.webtrade.webTrade.ui;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.bukkit.Material;
import org.bukkit.inventory.ItemStack;
import org.webtrade.webTrade.market.MarketManager;

import java.time.Duration;
import java.util.List;

/** Общие мелочи интерфейса: названия, деньги, иконки-кнопки. */
public final class Ui {

    /** Сколько живут колбэки кнопок диалога. Экран за это время точно успеют закрыть. */
    private static final Duration CALLBACK_LIFETIME = Duration.ofMinutes(30);

    private Ui() {
    }

    /**
     * Название предмета отдаём переводом — клиент подставит его на своём языке сам.
     * Именно поэтому в игровой версии не нужен справочник русских названий.
     */
    public static Component name(Material material) {
        return Component.translatable(material.translationKey());
    }

    public static String money(double value) {
        return MarketManager.money(value);
    }

    public static Component ares(double value) {
        return text(money(value) + " ар", NamedTextColor.GOLD);
    }

    /** Текст без ванильного курсива, которым Minecraft красит имена и описания предметов. */
    public static Component text(String value, NamedTextColor color) {
        return Component.text(value, color).decoration(TextDecoration.ITALIC, false);
    }

    public static Component text(String value) {
        return text(value, NamedTextColor.GRAY);
    }

    /** Предмет-иконка для сундучного меню: свой заголовок и описание. */
    public static ItemStack icon(Material material, Component title, List<Component> lore) {
        ItemStack stack = new ItemStack(material);
        stack.editMeta(meta -> {
            meta.displayName(title.decoration(TextDecoration.ITALIC, false));
            if (!lore.isEmpty()) {
                meta.lore(lore.stream()
                        .map(line -> line.decoration(TextDecoration.ITALIC, false))
                        .toList());
            }
        });
        return stack;
    }

    /** Иконка с количеством: показываем стак нужного размера, но не больше 64. */
    public static ItemStack icon(Material material, int amount, Component title, List<Component> lore) {
        ItemStack stack = icon(material, title, lore);
        stack.setAmount(Math.clamp(amount, 1, 64));
        return stack;
    }

    /** Тёмная заглушка для пустых мест: без подсказки, чтобы наведение не показывало пустую рамку. */
    public static ItemStack filler() {
        ItemStack stack = icon(Material.BLACK_STAINED_GLASS_PANE, Component.empty(), List.of());
        stack.editMeta(meta -> meta.setHideTooltip(true));
        return stack;
    }

    /** «30 000 шт. · ≈ 468 стаков» — добытчику проще прикинуть в стаках. */
    public static Component amount(String label, int units, NamedTextColor color) {
        String text = label + String.format(java.util.Locale.ROOT, "%,d", units).replace(',', ' ') + " шт.";
        Component line = text(text, color);
        if (units >= 64) {
            line = line.append(text(" · ≈ " + String.format(java.util.Locale.ROOT, "%,d", Math.round(units / 64.0))
                    .replace(',', ' ') + " ст.", NamedTextColor.DARK_GRAY));
        }
        return line;
    }

    /** Подсказка по клику: клавиша жёлтым, действие серым. */
    public static Component hint(String key, String action) {
        return text(key, NamedTextColor.YELLOW).append(text(" · " + action, NamedTextColor.GRAY));
    }

    /** Блеск зачарования — так в меню выделяется то, что сейчас важно (например, непустой контейнер). */
    public static ItemStack glint(ItemStack stack) {
        stack.editMeta(meta -> meta.setEnchantmentGlintOverride(true));
        return stack;
    }

    /** Настройки колбэка кнопки диалога: жмут сколько угодно раз, пока экран открыт. */
    public static ClickCallback.Options callback() {
        return ClickCallback.Options.builder()
                .uses(ClickCallback.UNLIMITED_USES)
                .lifetime(CALLBACK_LIFETIME)
                .build();
    }
}
