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

    /** Сколько позиций контейнера показываем в диалоге, прежде чем свернуть в «и ещё N». */
    private static final int MAX_LINES_IN_DIALOG = 8;
    /**
     * Потолок вставки. Формат диалога длину не ограничивает, но ответ приходит строкой NBT,
     * а там двухбайтовая длина — то есть 65535 байт. Берём с запасом: заказа на весь каталог
     * (979 позиций, ~31 КБ) хватает с большим избытком.
     */
    private static final int MAX_PASTE_LENGTH = 40000;
    /** Строк тоже должно хватать на весь каталог. */
    private static final int MAX_PASTE_LINES = 1200;

    private static final int WIDTH_SMALL = 200;
    private static final int WIDTH_MEDIUM = 240;
    private static final int WIDTH_WIDE = 310;

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
                button("Внести ары", "Положить руду на счёт", WIDTH_SMALL,
                        DepositGui::open),
                button("Забрать ары", "Снять руду со счёта", WIDTH_SMALL,
                        this::withdrawDialog),
                button("Создать контейнер", "Заказать ресурсы и оплатить", WIDTH_SMALL,
                        this::pasteDialog),
                button("Забрать контейнер", mine == 0 ? "Пока нет заказов" : "Заказов: " + mine,
                        WIDTH_SMALL, this::containers),
                button("СДАТЬ РЕСУРСЫ", "Отдать добытое и получить оплату", WIDTH_WIDE,
                        SellGui::open));

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text("Биржа ресурсов", NamedTextColor.GOLD))
                        .body(List.of(DialogBody.plainMessage(
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
                        .body(List.of(DialogBody.plainMessage(Ui.text(
                                "Доступно " + whole + " шт. Дробный остаток остаётся на счету."))))
                        .inputs(List.of(DialogInput.numberRange("ore",
                                        Ui.text("Руды", NamedTextColor.WHITE), 1, whole)
                                .initial((float) Math.min(whole, 64))
                                .step(1f)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Ui.text("Снять", NamedTextColor.GREEN))
                                .action(DialogAction.customClick((view, audience) -> {
                                    Float raw = view.getFloat("ore");
                                    int ore = raw == null ? 0 : Math.round(raw);
                                    later(() -> doWithdraw(player, ore));
                                }, Ui.callback()))
                                .build(),
                        ActionButton.builder(Ui.text("← В меню", NamedTextColor.YELLOW))
                                .action(DialogAction.customClick(
                                        (view, audience) -> later(() -> mainMenu(player)), Ui.callback()))
                                .build()))));
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
                        .body(List.of(DialogBody.plainMessage(Ui.text(
                                "Зачислено " + Ui.money(ares) + " ар. На счету: "
                                        + Ui.money(accounts.balance(player.getUniqueId())) + " ар.",
                                NamedTextColor.GOLD))))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        button("Внести ещё", null, WIDTH_MEDIUM, DepositGui::open),
                        button("← В меню", null, WIDTH_MEDIUM, this::mainMenu)))));
    }

    // ---------- сдача ресурсов ----------

    /**
     * Итог сдачи с кнопкой «Сдать ещё»: за один заход обычно приносят несколько
     * инвентарей подряд, и гонять человека обратно в меню за каждым — издевательство.
     */
    public void sellResult(Player player, double earned, int stored) {
        List<DialogBody> body = new ArrayList<>();
        body.add(DialogBody.plainMessage(Ui.text(
                "Начислено: " + Ui.money(earned) + " ар", NamedTextColor.GOLD)));
        if (stored > 0) {
            body.add(DialogBody.plainMessage(Ui.text(
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
                                .action(DialogAction.customClick((view, audience) ->
                                        later(() -> SellGui.open(player)), Ui.callback()))
                                .build(),
                        button("← В меню", null, WIDTH_MEDIUM, this::mainMenu)))));
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
                .width(WIDTH_WIDE)
                .action(DialogAction.customClick((view, audience) -> {
                    String name = view.getText("name");
                    String raw = view.getText("list");
                    later(() -> applyPastedList(player, name, raw));
                }, Ui.callback()))
                .build());
        if (!calculatorUrl.isEmpty()) {
            buttons.add(ActionButton.builder(Ui.text("Калькулятор", NamedTextColor.AQUA))
                    .tooltip(Ui.text("Посчитать список на сайте и скопировать его"))
                    .width(WIDTH_WIDE)
                    .action(DialogAction.staticAction(ClickEvent.openUrl(calculatorUrl)))
                    .build());
        }
        buttons.add(button("← В меню", null, WIDTH_MEDIUM, this::mainMenu));

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text("Создать контейнер", NamedTextColor.GOLD))
                        .body(List.of(DialogBody.plainMessage(Ui.text(
                                "Вставьте список: по строке на ресурс, название и количество.\n"
                                        + "Например «STONE 3000» или «oak_planks 12s» (s — стаки).\n"
                                        + "Ниже назовите заказ, чтобы отличать его от других — например «Храм»."))))
                        // Список первым: клиент рисует счётчик символов под многострочным полем поверх
                        // того, что ниже. Под ним должна быть подпись поля (она слева, счётчик справа),
                        // а не кнопка «Оплатить» во всю ширину.
                        .inputs(List.of(
                                DialogInput.text("list", Ui.text("Список", NamedTextColor.WHITE))
                                        .maxLength(MAX_PASTE_LENGTH)
                                        .width(300)
                                        .multiline(TextDialogInput.MultilineOptions.create(
                                                MAX_PASTE_LINES, 260))
                                        .build(),
                                DialogInput.text("name", Ui.text("Название", NamedTextColor.WHITE))
                                        .initial(cartOf(player).title())
                                        .maxLength(Container.MAX_NAME)
                                        .width(300)
                                        .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(buttons).columns(1).build())));
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
                        .body(List.of(DialogBody.plainMessage(Ui.text(
                                "Часть названия: камень, доски, STONE или oak."))))
                        .inputs(List.of(DialogInput.text("query", Ui.text("Запрос", NamedTextColor.WHITE))
                                .initial(catalog.filterText())
                                .maxLength(48)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.multiAction(List.of(
                                ActionButton.builder(Ui.text("Искать", NamedTextColor.GREEN))
                                        .width(WIDTH_MEDIUM)
                                        .action(DialogAction.customClick((view, audience) -> {
                                            String query = view.getText("query");
                                            later(() -> {
                                                catalog.filter(query);
                                                catalog.open(player);
                                            });
                                        }, Ui.callback()))
                                        .build(),
                                button("← В каталог", null, WIDTH_MEDIUM,
                                        p -> catalog.open(p))))
                        .columns(1)
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
                                                NamedTextColor.GOLD)))
                                        .build(),
                                DialogBody.plainMessage(Ui.text(
                                        "Сколько штук заказать? Ноль уберёт позицию."))))
                        .inputs(List.of(DialogInput.text("amount",
                                        Ui.text("Количество", NamedTextColor.WHITE))
                                .initial(String.valueOf(current))
                                .maxLength(6)
                                .build()))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Ui.text("Записать", NamedTextColor.GREEN))
                                .action(DialogAction.customClick((view, audience) -> {
                                    String raw = view.getText("amount");
                                    later(() -> applyAmount(player, material, raw));
                                }, Ui.callback()))
                                .build(),
                        ActionButton.builder(Ui.text("← В каталог", NamedTextColor.YELLOW))
                                .action(DialogAction.customClick((view, audience) ->
                                        later(() -> catalogFor(player).open(player)), Ui.callback()))
                                .build()))));
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
                            .build()))
                    .build());
        }
        if (cart.size() > MAX_LINES_IN_DIALOG) {
            body.add(DialogBody.plainMessage(Ui.text(
                    "…и ещё " + (cart.size() - MAX_LINES_IN_DIALOG) + " позиций.")));
        }
        body.add(DialogBody.plainMessage(Ui.text(
                "Позиций: " + cart.size() + ". Итого: " + Ui.money(total)
                        + " ар. На счету: " + Ui.money(balance) + " ар.",
                total <= balance ? NamedTextColor.GOLD : NamedTextColor.RED)));
        if (total > balance) {
            body.add(DialogBody.plainMessage(Ui.text(
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
                                        .width(WIDTH_WIDE)
                                        .action(DialogAction.customClick((view, audience) ->
                                                later(() -> payCart(player)), Ui.callback()))
                                        .build(),
                                button("Очистить заказ", "Убрать все позиции", WIDTH_MEDIUM,
                                        p -> {
                                            cartOf(p).clear();
                                            mainMenu(p);
                                        }),
                                button("← В меню", "Заказ останется набранным", WIDTH_MEDIUM,
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
            buttons.add(button(container.label() + " — " + percent + "%", hint,
                    WIDTH_WIDE, p -> containerDetail(p, container.id())));
        }
        buttons.add(button("← В меню", null, WIDTH_MEDIUM, this::mainMenu));

        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text("Мои контейнеры", NamedTextColor.GOLD))
                        .body(List.of(DialogBody.plainMessage(Ui.text(
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
                            .build()))
                    .build());
        }
        if (container.lines().size() > MAX_LINES_IN_DIALOG) {
            body.add(DialogBody.plainMessage(Ui.text(
                    "…и ещё " + (container.lines().size() - MAX_LINES_IN_DIALOG) + " позиций.")));
        }
        boolean cancelled = container.status() == Container.Status.CANCELLED;
        body.add(DialogBody.plainMessage(Ui.text("Собрано " + progressPercent(container) + "%"
                + (cancelled ? " · заказ отменён, спроса по нему больше нет" : ""))));

        // Кнопку «Открыть» показываем всегда, даже если забирать пока нечего: заглянуть
        // внутрь и убедиться в этом — нормальное желание, а пустое окно так и скажет.
        boolean empty = market.isEmptyForOwner(container);
        List<ActionButton> buttons = new ArrayList<>();
        buttons.add(button("Открыть контейнер",
                empty ? "Пока пусто — можно просто заглянуть" : "Забрать собранное",
                WIDTH_WIDE, p -> new WithdrawGui(this, container).open(p)));
        if (!cancelled) {
            buttons.add(button("Отменить заказ", "Вернуть ары за несобранное", WIDTH_MEDIUM,
                    p -> cancelDialog(p, container)));
        }
        buttons.add(button("← В меню", null, WIDTH_MEDIUM, this::mainMenu));

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
                        .body(List.of(DialogBody.plainMessage(Ui.text(
                                "Вернётся " + Ui.money(amount) + " ар за несобранное. "
                                        + "Уже собранное останется — его можно будет забрать."))))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.confirmation(
                        ActionButton.builder(Ui.text("Отменить и вернуть", NamedTextColor.RED))
                                .action(DialogAction.customClick((view, audience) ->
                                        later(() -> doCancel(player, container)), Ui.callback()))
                                .build(),
                        ActionButton.builder(Ui.text("Оставить", NamedTextColor.YELLOW))
                                .action(DialogAction.customClick((view, audience) ->
                                        later(() -> containerDetail(player, container.id())),
                                        Ui.callback()))
                                .build()))));
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
        notice(player, title, message, "В меню", this::mainMenu);
    }

    /** То же, но возвращает туда, откуда игрок пришёл, — например обратно к контейнерам. */
    public void notice(Player player, String title, String message,
                       String backLabel, Consumer<Player> back) {
        show(player, Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(Ui.text(title, NamedTextColor.GOLD))
                        .body(List.of(DialogBody.plainMessage(Ui.text(message, NamedTextColor.WHITE))))
                        .afterAction(DialogBase.DialogAfterAction.CLOSE)
                        .build())
                .type(DialogType.notice(button(backLabel, null, WIDTH_MEDIUM, back)))));
    }

    /**
     * Кнопка диалога. Игрока берём из Audience, который отдаёт колбэк, — так кнопка
     * не держит ссылку на того, кто её строил.
     */
    private ActionButton button(String label, String tooltip, int width, Consumer<Player> onClick) {
        ActionButton.Builder builder = ActionButton.builder(Ui.text(label, NamedTextColor.WHITE))
                .width(width)
                .action(DialogAction.customClick((view, audience) -> {
                    if (audience instanceof Player player) {
                        later(() -> onClick.accept(player));
                    }
                }, Ui.callback()));
        if (tooltip != null) {
            builder.tooltip(Ui.text(tooltip));
        }
        return builder.build();
    }

    private void show(Player player, Dialog dialog) {
        player.showDialog(dialog);
    }

    /** Работу с биржей и инвентарями делаем в главном потоке. */
    private void later(Runnable action) {
        plugin.getServer().getScheduler().runTask(plugin, action);
    }
}
