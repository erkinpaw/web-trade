package org.webtrade.webTrade.ui;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.input.DialogInput;
import io.papermc.paper.registry.data.dialog.input.TextDialogInput;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.Material;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.plugin.Plugin;
import org.webtrade.webTrade.account.AccountManager;
import org.webtrade.webTrade.currency.CurrencyService;
import org.webtrade.webTrade.currency.DepositGui;
import org.webtrade.webTrade.market.Container;
import org.webtrade.webTrade.market.MarketManager;
import org.webtrade.webTrade.market.OrderList;
import org.webtrade.webTrade.market.SellGui;
import org.webtrade.webTrade.resource.ResourceRegistry;
import org.webtrade.webTrade.resource.ResourceType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Все экраны биржи в одном месте.
 * <p>
 * Разделение простое: списки, где позиций много, — сундучные меню с листалкой;
 * ввод чисел, подтверждения и статусы — диалоги. Так сложилось не от хорошей жизни:
 * диалог не умеет показать сотню строк с иконками, а сундук не умеет принять число.
 */
public final class Screens {

    /**
     * Сколько позиций контейнера показываем в диалоге, прежде чем свернуть в «и ещё N».
     * Больше пяти — и кнопки под списком приходится искать прокруткой.
     */
    private static final int MAX_LINES_IN_DIALOG = 5;
    /**
     * Потолок вставки. Формат диалога длину не ограничивает, но ответ приходит строкой NBT,
     * а там двухбайтовая длина — то есть 65535 байт. Берём с запасом: заказа на весь каталог
     * (979 позиций, ~31 КБ) хватает с большим избытком.
     */
    private static final int MAX_PASTE_LENGTH = 40000;
    /** Строк тоже должно хватать на весь каталог. */
    private static final int MAX_PASTE_LINES = 1200;
    /**
     * Высота поля списка. Вставленное всё равно прокручивается, а при выше поле
     * выталкивало кнопки за нижний край экрана на обычном масштабе интерфейса.
     */
    private static final int PASTE_HEIGHT = 100;

    /*
     * Ширины диалога. Клиент раскладывает кнопки сеткой с зазором 2 пикселя и центрирует
     * каждую в своей ячейке, так что ровные края получаются, только если широкая кнопка
     * ровно вдвое шире узкой плюс зазор. Поля ввода и текст тянем на ту же ширину,
     * чтобы весь экран стоял одной колонкой.
     */
    private static final int WIDTH_HALF = 160;
    private static final int WIDTH_FULL = WIDTH_HALF * 2 + 2;
    /** Текст в кнопке рисуется с отступом по краям: столько оставляем свободным. */
    private static final int BUTTON_PADDING = 12;
    /** Описание рядом с иконкой предмета: иконка 16 пикселей плюс небольшой зазор. */
    private static final int ITEM_TEXT_WIDTH = WIDTH_FULL - 24;

    private final Plugin plugin;
    private final AccountManager accounts;
    private final MarketManager market;
    private final ResourceRegistry resources;
    private final CurrencyService currency;
    private final String calculatorUrl;

    private final Map<UUID, Cart> carts = new ConcurrentHashMap<>();
    private final Map<UUID, CatalogGui> catalogs = new ConcurrentHashMap<>();

    public Screens(Plugin plugin, AccountManager accounts, MarketManager market,
                   ResourceRegistry resources, CurrencyService currency, String calculatorUrl) {
        this.plugin = plugin;
        this.accounts = accounts;
        this.market = market;
        this.resources = resources;
        this.currency = currency;
        this.calculatorUrl = calculatorUrl == null ? "" : calculatorUrl.trim();
    }

    public MarketManager market() {
        return market;
    }

    public ResourceRegistry resources() {
        return resources;
    }

    /** Русское название из ассетов калькулятора; null — их ещё не скачали (тогда ищем по STONE и т.п.). */
    public String russianName(org.bukkit.Material material) {
        return plugin instanceof org.webtrade.webTrade.WebTrade webTrade
                ? webTrade.calculatorAssets().names().get(material.name()) : null;
    }

    public Cart cartOf(Player player) {
        return carts.computeIfAbsent(player.getUniqueId(), uuid -> new Cart());
    }

    /** Игрок вышел — черновик контейнера и его каталог больше не нужны. */
    public void forget(UUID uuid) {
        carts.remove(uuid);
        catalogs.remove(uuid);
    }

    // ---------- главное меню ----------

    public void mainMenu(Player player) {
        double balance = accounts.balance(player.getUniqueId());
        int mine = market.containersOf(player.getUniqueId()).size();

        // Сетка заполняется слева направо, поэтому одинокая широкая кнопка возможна только
        // последней: при нечётном числе кнопок последняя остаётся одна в ряду.
        // Отсюда раскладка «пара / пара / широкая», а не «пара / широкая / пара».
        List<ActionButton> buttons = List.of(
                button("Внести ары", "Положить руду на счёт", WIDTH_HALF,
                        DepositGui::open),
                button("Забрать ары", "Снять руду со счёта", WIDTH_HALF,
                        this::withdrawDialog),
                button("Создать контейнер", "Заказать ресурсы и оплатить", WIDTH_HALF,
                        this::pasteDialog),
                button("Забрать контейнер", mine == 0 ? "Пока нет заказов" : "Заказов: " + mine,
                        WIDTH_HALF, this::containers),
                button("СДАТЬ РЕСУРСЫ", "Отдать добытое и получить оплату", WIDTH_FULL,
                        SellGui::open));

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text("Биржа ресурсов", NamedTextColor.GOLD))
                        .body(List.of(message(
                                Ui.text("На счету: " + Ui.money(balance) + " ар", NamedTextColor.GOLD))))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build())));
    }

    // ---------- ары ----------

    public void withdrawDialog(Player player) {
        double balance = accounts.balance(player.getUniqueId());
        int whole = (int) Math.floor(balance / currency.orePerAre());
        if (whole <= 0) {
            notice(player, "Снять нечего", "На счету " + Ui.money(balance)
                    + " ар — этого не хватает даже на одну руду.");
            return;
        }

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text("Забрать ары", NamedTextColor.GOLD))
                        .body(List.of(message(Ui.text(
                                "Доступно " + whole + " шт. Дробный остаток остаётся на счету."))))
                        .inputs(List.of(DialogInput.numberRange("ore",
                                        Ui.text("Руды", NamedTextColor.WHITE), 1, whole)
                                .width(WIDTH_FULL)
                                .initial((float) Math.min(whole, 64))
                                .step(1f)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Ui.text("Снять", NamedTextColor.GREEN))
                                .width(WIDTH_HALF)
                                .action(DialogAction.customClick((view, audience) -> {
                                    Float raw = view.getFloat("ore");
                                    int ore = raw == null ? 0 : Math.round(raw);
                                    later(player, () -> doWithdraw(player, ore));
                                }, Ui.callback()))
                                .build(),
                        button("← В меню", null, WIDTH_HALF, this::mainMenu)))));
    }

    private void doWithdraw(Player player, int ore) {
        if (ore <= 0) {
            return;
        }
        int given = currency.withdrawOre(player, ore);
        if (given < 0) {
            player.sendMessage(Ui.text("Недостаточно средств.", NamedTextColor.RED));
            return;
        }
        player.sendMessage(Ui.text("Выдано " + given + " руды.", NamedTextColor.GREEN));
    }

    /** Итог депозита с возвратом в меню — чтобы не оставлять игрока на пустом экране. */
    public void depositResult(Player player, double ares) {
        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text("Ары зачислены", NamedTextColor.GOLD))
                        .body(List.of(message(Ui.text(
                                "Зачислено " + Ui.money(ares) + " ар. На счету: "
                                        + Ui.money(accounts.balance(player.getUniqueId())) + " ар.",
                                NamedTextColor.GOLD))))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        button("Внести ещё", null, WIDTH_HALF, DepositGui::open),
                        button("← В меню", null, WIDTH_HALF, this::mainMenu)))));
    }

    // ---------- сдача ресурсов ----------

    /**
     * Итог сдачи с кнопкой «Сдать ещё»: за один заход обычно приносят несколько
     * инвентарей подряд, и гонять человека обратно в меню за каждым — издевательство.
     */
    public void sellResult(Player player, double earned, int stored) {
        List<DialogBody> body = new ArrayList<>();
        body.add(message(Ui.text(
                "Начислено: " + Ui.money(earned) + " ар", NamedTextColor.GOLD)));
        if (stored > 0) {
            body.add(message(Ui.text(
                    stored + " шт. легли на склад — спроса на них пока нет. "
                            + "Оплата придёт сама, когда появится.", NamedTextColor.YELLOW)));
        }

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text("Ресурсы сданы", NamedTextColor.GOLD))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Ui.text("Сдать ещё", NamedTextColor.GREEN))
                                .width(WIDTH_HALF)
                                .action(DialogAction.customClick((view, audience) ->
                                        later(player, () -> SellGui.open(player)), Ui.callback()))
                                .build(),
                        button("← В меню", null, WIDTH_HALF, this::mainMenu)))));
    }

    // ---------- создание контейнера ----------

    /**
     * Вставка готового списка. Набирать сотни позиций кликами невозможно, поэтому
     * основной путь такой: список считается в калькуляторе и вставляется сюда одним куском.
     */
    public void pasteDialog(Player player) {
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(ActionButton.builder(Ui.text("Оплатить", NamedTextColor.GREEN))
                .tooltip(Ui.text("Прочитать список и показать итоговую цену"))
                .width(WIDTH_HALF)
                .action(DialogAction.customClick((view, audience) -> {
                    String name = view.getText("name");
                    String raw = view.getText("list");
                    later(player, () -> applyPastedList(player, name, raw));
                }, Ui.callback()))
                .build());
        if (!calculatorUrl.isEmpty()) {
            buttons.add(ActionButton.builder(Ui.text("Калькулятор", NamedTextColor.AQUA))
                    .tooltip(Ui.text("Посчитать список на сайте и скопировать его"))
                    .width(WIDTH_HALF)
                    .action(DialogAction.staticAction(ClickEvent.openUrl(calculatorUrl)))
                    .build());
        }
        // Две кнопки в ряд экономят строку: поле списка высокое, и с тремя кнопками
        // в столбик нижняя уезжала за край экрана. Без калькулятора «в меню» встаёт парой.
        buttons.add(button("← В меню", null, calculatorUrl.isEmpty() ? WIDTH_HALF : WIDTH_FULL,
                this::mainMenu));

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text("Создать контейнер", NamedTextColor.GOLD))
                        // Каждая строка короче ширины диалога: длинные клиент переносит
                        // посреди фразы, и текст рассыпается лесенкой.
                        .body(List.of(message(Ui.text(
                                "Вставьте список: по строке на ресурс.\n"
                                        + "Например «STONE 3000» или «oak_planks 12s» (s — стаки).\n"
                                        + "Под списком — название, чтобы отличать заказы."))))
                        // Список стоит первым не случайно. Под многострочным полем клиент
                        // всегда пишет счётчик символов и места под него не оставляет:
                        // будь поле последним, счётчик лёг бы на кнопки. А так под ним
                        // подпись «Название» — она слева, счётчик справа, они не сталкиваются.
                        .inputs(List.of(
                                DialogInput.text("list", Ui.text("Список", NamedTextColor.WHITE))
                                        .maxLength(MAX_PASTE_LENGTH)
                                        .width(WIDTH_FULL)
                                        .multiline(TextDialogInput.MultilineOptions.create(
                                                MAX_PASTE_LINES, PASTE_HEIGHT))
                                        .build(),
                                DialogInput.text("name", Ui.text("Название", NamedTextColor.WHITE))
                                        .initial(cartOf(player).title())
                                        .maxLength(Container.MAX_NAME)
                                        .width(WIDTH_FULL)
                                        .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(buttons).columns(2).build())));
    }

    private void applyPastedList(Player player, String name, String raw) {
        cartOf(player).setTitle(name);
        OrderList.Result result = OrderList.parse(raw, resources);
        if (result.isEmpty()) {
            notice(player, "Список не прочитан",
                    result.problems().isEmpty()
                            ? "Список пуст — вставьте строки вида «STONE 3000»."
                            : "Ни одной строки прочитать не вышло. Первая из них: «"
                                    + result.problems().get(0) + "»");
            return;
        }

        Cart cart = cartOf(player);
        result.lines().forEach((material, amount) -> cart.set(material, cart.get(material) + amount));

        if (!result.problems().isEmpty()) {
            player.sendMessage(Ui.text("Пропущено строк: " + result.problems().size()
                    + " — их не удалось прочитать.", NamedTextColor.YELLOW));
            result.problems().stream().limit(5).forEach(line ->
                    player.sendMessage(Ui.text("  " + line, NamedTextColor.GRAY)));
        }
        cartDialog(player);
    }

    /** Каталог остаётся как ручной путь: пригодится, когда надо добавить одну позицию. */
    public void catalog(Player player) {
        catalogFor(player).open(player);
    }

    private CatalogGui catalogFor(Player player) {
        return catalogs.computeIfAbsent(player.getUniqueId(), uuid -> new CatalogGui(this, cartOf(player)));
    }

    public void searchDialog(Player player, CatalogGui catalog) {
        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text("Поиск ресурса", NamedTextColor.AQUA))
                        .body(List.of(message(Ui.text(
                                "Часть названия: камень, доски, STONE или oak."))))
                        .inputs(List.of(DialogInput.text("query", Ui.text("Запрос", NamedTextColor.WHITE))
                                .width(WIDTH_FULL)
                                .initial(catalog.filterText())
                                .maxLength(48)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(List.of(
                                ActionButton.builder(Ui.text("Искать", NamedTextColor.GREEN))
                                        .width(WIDTH_HALF)
                                        .action(DialogAction.customClick((view, audience) -> {
                                            String query = view.getText("query");
                                            later(player, () -> {
                                                catalog.filter(query);
                                                catalog.open(player);
                                            });
                                        }, Ui.callback()))
                                        .build(),
                                button("← В каталог", null, WIDTH_HALF,
                                        p -> catalog.open(p))))
                        .columns(2)
                        .build())));
    }

    public void amountDialog(Player player, Material material) {
        ResourceType type = resources.get(material);
        if (type == null) {
            return;
        }
        int current = cartOf(player).get(material);

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.name(material))
                        .body(List.of(
                                DialogBody.item(new ItemStack(material))
                                        .description(DialogBody.plainMessage(Ui.text(
                                                Ui.money(type.pricePerStack()) + " ар за стак",
                                                NamedTextColor.GOLD), ITEM_TEXT_WIDTH))
                                        .build(),
                                message(Ui.text(
                                        "Сколько штук заказать? Ноль уберёт позицию."))))
                        .inputs(List.of(DialogInput.text("amount",
                                        Ui.text("Количество", NamedTextColor.WHITE))
                                .width(WIDTH_FULL)
                                .initial(String.valueOf(current))
                                .maxLength(6)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Ui.text("Записать", NamedTextColor.GREEN))
                                .width(WIDTH_HALF)
                                .action(DialogAction.customClick((view, audience) -> {
                                    String raw = view.getText("amount");
                                    later(player, () -> applyAmount(player, material, raw));
                                }, Ui.callback()))
                                .build(),
                        button("← В каталог", null, WIDTH_HALF, p -> catalogFor(p).open(p))))));
    }

    private void applyAmount(Player player, Material material, String raw) {
        int amount;
        try {
            amount = raw == null || raw.isBlank() ? 0 : Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            player.sendMessage(Ui.text("Это не число: " + raw, NamedTextColor.RED));
            catalogFor(player).open(player);
            return;
        }
        cartOf(player).set(material, amount);
        catalogFor(player).open(player);
    }

    /** Проверка заказа перед оплатой. */
    public void cartDialog(Player player) {
        Cart cart = cartOf(player);
        if (cart.isEmpty()) {
            notice(player, "Заказ пуст", "Вставьте список или выберите ресурсы в каталоге.");
            return;
        }
        double total = market.price(cart.lines());
        double balance = accounts.balance(player.getUniqueId());

        List<DialogBody> body = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<Material, Integer> line : cart.lines().entrySet()) {
            if (shown++ >= MAX_LINES_IN_DIALOG) {
                break;
            }
            ResourceType type = resources.get(line.getKey());
            double cost = type == null ? 0.0 : type.pricePerUnit() * line.getValue();
            body.add(DialogBody.item(new ItemStack(line.getKey()))
                    .description(DialogBody.plainMessage(Component.text()
                            .append(Ui.name(line.getKey()))
                            .append(Ui.text(" — " + line.getValue() + " шт. за "
                                    + Ui.money(cost) + " ар", NamedTextColor.GRAY))
                            .build(), ITEM_TEXT_WIDTH))
                    .build());
        }
        if (cart.size() > MAX_LINES_IN_DIALOG) {
            body.add(message(Ui.text(
                    "…и ещё " + (cart.size() - MAX_LINES_IN_DIALOG) + " позиций.")));
        }
        body.add(message(Ui.text(
                "Позиций: " + cart.size() + ". Итого: " + Ui.money(total)
                        + " ар. На счету: " + Ui.money(balance) + " ар.",
                total <= balance ? NamedTextColor.GOLD : NamedTextColor.RED)));
        if (total > balance) {
            body.add(message(Ui.text(
                    "Не хватает " + Ui.money(total - balance) + " ар.", NamedTextColor.RED)));
        }

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text(cart.title().isBlank()
                                ? "Оплата контейнера"
                                : "Оплата: " + cart.title(), NamedTextColor.GOLD))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(List.of(
                                ActionButton.builder(Ui.text("Подтвердить оплату", NamedTextColor.GREEN))
                                        .width(WIDTH_FULL)
                                        .action(DialogAction.customClick((view, audience) ->
                                                later(player, () -> payCart(player)), Ui.callback()))
                                        .build(),
                                button("Очистить заказ", "Убрать все позиции", WIDTH_FULL,
                                        p -> {
                                            cartOf(p).clear();
                                            mainMenu(p);
                                        }),
                                button("← В меню", "Заказ останется набранным", WIDTH_FULL,
                                        this::mainMenu)))
                        .columns(1)
                        .build())));
    }

    private void payCart(Player player) {
        Cart cart = cartOf(player);
        if (cart.isEmpty()) {
            return;
        }
        MarketManager.CreateResult result = market.create(
                player.getUniqueId(), player.getName(), cart.title(), cart.snapshot());
        if (!result.ok()) {
            notice(player, "Не получилось", result.message());
            return;
        }
        String label = cart.title().isBlank()
                ? "Контейнер #" + result.containerId()
                : "Заказ «" + cart.title() + "» (#" + result.containerId() + ")";
        cart.clear();
        notice(player, "Контейнер создан",
                label + " оплачен на " + Ui.money(result.cost())
                        + " ар. Ресурсы попали в спрос — добытчики их увидят.");
    }

    // ---------- контейнеры ----------

    public void containers(Player player) {
        List<Container> mine = market.containersOf(player.getUniqueId());
        if (mine.isEmpty()) {
            notice(player, "Контейнеров нет", "Создайте заказ через «Создать контейнер».");
            return;
        }
        if (mine.size() == 1) {
            containerDetail(player, mine.get(0).id());
            return;
        }

        List<ActionButton> buttons = new ArrayList<>();
        for (Container container : mine) {
            int percent = progressPercent(container);
            String hint = market.isEmptyForOwner(container)
                    ? "собрано " + percent + "%, забирать пока нечего"
                    : "собрано " + percent + "%, есть что забрать";
            String suffix = container.status() == Container.Status.CANCELLED
                    ? " — отменён"
                    : " — " + percent + "%";
            String name = Ui.fit(container.label(), WIDTH_FULL - BUTTON_PADDING - Ui.width(suffix));
            buttons.add(button(name + suffix, hint,
                    WIDTH_FULL, p -> containerDetail(p, container.id())));
        }
        buttons.add(button("← В меню", null, WIDTH_FULL, this::mainMenu));

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text("Мои контейнеры", NamedTextColor.GOLD))
                        .body(List.of(message(Ui.text(
                                "Заказов: " + mine.size() + ". Выберите нужный."))))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(buttons).columns(1).build())));
    }

    public void containerDetail(Player player, int id) {
        Container container = market.get(id);
        if (container == null || !container.owner().equals(player.getUniqueId())) {
            notice(player, "Не найдено", "Контейнер #" + id + " вам не принадлежит.");
            return;
        }

        List<DialogBody> body = new ArrayList<>();
        int shown = 0;
        for (Map.Entry<Material, Container.Line> entry : container.lines().entrySet()) {
            if (shown++ >= MAX_LINES_IN_DIALOG) {
                break;
            }
            Container.Line line = entry.getValue();
            String ready = line.readyToWithdraw() > 0 ? ", к выдаче " + line.readyToWithdraw() : "";
            body.add(DialogBody.item(new ItemStack(entry.getKey()))
                    .description(DialogBody.plainMessage(Component.text()
                            .append(Ui.name(entry.getKey()))
                            .append(Ui.text(" — " + line.collected() + "/" + line.requested() + ready,
                                    NamedTextColor.GRAY))
                            .build(), ITEM_TEXT_WIDTH))
                    .build());
        }
        if (container.lines().size() > MAX_LINES_IN_DIALOG) {
            body.add(message(Ui.text(
                    "…и ещё " + (container.lines().size() - MAX_LINES_IN_DIALOG) + " позиций.")));
        }
        boolean cancelled = container.status() == Container.Status.CANCELLED;
        body.add(message(Ui.text("Собрано " + progressPercent(container) + "%"
                + (cancelled ? " · заказ отменён, спроса по нему больше нет" : ""))));

        // Кнопку «Открыть» показываем всегда, даже если забирать пока нечего: заглянуть
        // внутрь и убедиться в этом — нормальное желание, а пустое окно так и скажет.
        boolean empty = market.isEmptyForOwner(container);
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("Открыть контейнер",
                empty ? "Пока пусто — можно просто заглянуть" : "Забрать собранное",
                WIDTH_FULL, p -> new WithdrawGui(this, container).open(p)));
        if (!cancelled) {
            buttons.add(button("Отменить заказ", "Вернуть ары за несобранное", WIDTH_FULL,
                    p -> cancelDialog(p, container)));
        }
        buttons.add(button("← В меню", null, WIDTH_FULL, this::mainMenu));

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text(container.label(), NamedTextColor.GOLD))
                        .body(body)
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(buttons).columns(1).build())));
    }

    private void cancelDialog(Player player, Container container) {
        double refund = 0.0;
        for (Map.Entry<Material, Container.Line> entry : container.lines().entrySet()) {
            ResourceType type = resources.get(entry.getKey());
            if (type != null) {
                refund += type.pricePerUnit() * entry.getValue().outstanding();
            }
        }
        double amount = refund;

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text("Отменить заказ?", NamedTextColor.RED))
                        .body(List.of(message(Ui.text(
                                "Вернётся " + Ui.money(amount) + " ар за несобранное.\n"
                                        + "Собранное останется — его можно будет забрать."))))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Ui.text("Отменить и вернуть", NamedTextColor.RED))
                                .width(WIDTH_HALF)
                                .action(DialogAction.customClick((view, audience) ->
                                        later(player, () -> doCancel(player, container)), Ui.callback()))
                                .build(),
                        button("Оставить", null, WIDTH_HALF, p -> containerDetail(p, container.id()))))));
    }

    private void doCancel(Player player, Container container) {
        double refund = market.cancel(container);
        notice(player, "Заказ отменён", "Возвращено " + Ui.money(refund) + " ар."
                        + (market.isEmptyForOwner(container) ? "" : " Собранное осталось — заберите его."),
                "← К контейнерам", this::containers);
    }

    private int progressPercent(Container container) {
        int requested = 0;
        int collected = 0;
        for (Container.Line line : container.lines().values()) {
            requested += line.requested();
            collected += line.collected();
        }
        return requested == 0 ? 100 : (int) Math.round(collected * 100.0 / requested);
    }

    // ---------- мелочи ----------

    /** Простое сообщение с возвратом в меню. */
    public void notice(Player player, String title, String message) {
        notice(player, title, message, "← В меню", this::mainMenu);
    }

    /** То же, но возвращает туда, откуда игрок пришёл, — например обратно к контейнерам. */
    public void notice(Player player, String title, String message,
                       String backLabel, Consumer<Player> back) {
        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text(title, NamedTextColor.GOLD))
                        .body(List.of(message(Ui.text(message, NamedTextColor.WHITE))))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.notice(button(backLabel, null, WIDTH_HALF, back)))));
    }

    /**
     * Кнопка диалога. Игрока берём из Audience, который отдаёт колбэк, — так кнопка
     * не держит ссылку на того, кто её строил.
     */
    private ActionButton button(String label, String tooltip, int width, Consumer<Player> onClick) {
        ActionButton.Builder builder = ActionButton
                .builder(Ui.text(Ui.fit(label, width - BUTTON_PADDING), NamedTextColor.WHITE))
                .width(width)
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player player) {
                        later(player, () -> onClick.accept(player));
                    }
                }, Ui.callback()));
        if (tooltip != null) {
            builder.tooltip(Ui.text(tooltip));
        }
        return builder.build();
    }

    /** Текст диалога во всю ширину — вровень с кнопками и полями ввода. */
    private static DialogBody message(Component text) {
        return DialogBody.plainMessage(text, WIDTH_FULL);
    }

    private void show(Player player, Dialog dialog) {
        player.showDialog(dialog);
    }

    /** Работу с инвентарём игрока делаем в его потоке (на Folia — поток его региона, на Paper — главный). */
    private void later(Player player, Runnable action) {
        player.getScheduler().run(plugin, t -> action.run(), null);
    }
}
