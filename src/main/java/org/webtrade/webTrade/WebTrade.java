package org.webtrade.webTrade;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.webtrade.webTrade.account.AccountManager;
import org.webtrade.webTrade.assets.CalculatorAssets;
import org.webtrade.webTrade.account.JoinListener;
import org.webtrade.webTrade.command.CalculatorCommand;
import org.webtrade.webTrade.command.RemoteAccess;
import org.webtrade.webTrade.command.ScreenCommand;
import org.webtrade.webTrade.command.WithdrawCommand;
import org.webtrade.webTrade.currency.CurrencyListener;
import org.webtrade.webTrade.currency.CurrencyService;
import org.webtrade.webTrade.currency.DepositGui;
import org.webtrade.webTrade.market.MarketManager;
import org.webtrade.webTrade.market.SellGui;
import org.webtrade.webTrade.market.SellListener;
import org.webtrade.webTrade.command.StandCommand;
import org.webtrade.webTrade.resource.CalculatorExport;
import org.webtrade.webTrade.resource.ResourceRegistry;
import org.webtrade.webTrade.schematic.LegacyBlocks;
import org.webtrade.webTrade.stand.DemandContent;
import org.webtrade.webTrade.stand.StandManager;
import org.webtrade.webTrade.ui.DemandGui;
import org.webtrade.webTrade.ui.GuiListener;
import org.webtrade.webTrade.ui.Screens;
import org.webtrade.webTrade.ui.StockGui;
import org.webtrade.webTrade.ui.Ui;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

public final class WebTrade extends JavaPlugin {

    /** Как часто страхуемся автосохранением (тики: 20 тиков = 1 сек). */
    private static final long AUTOSAVE_TICKS = 20L * 60 * 5;

    private AccountManager accounts;
    private ResourceRegistry resources;
    private CurrencyService currency;
    private MarketManager market;
    private Screens screens;
    private StandManager stands;
    private volatile CalculatorAssets.Result calculatorAssets = CalculatorAssets.Result.empty();

    @Override
    public void onEnable() {
        saveDefaultConfig();
        // Новые настройки должны появляться и у тех, у кого config.yml уже лежит:
        // без этого добавленные ключи админ просто не увидит.
        getConfig().options().copyDefaults(true);
        saveConfig();
        getDataFolder().mkdirs();
        var config = getConfig();

        this.accounts = new AccountManager(getDataFolder());
        this.resources = new ResourceRegistry(getDataFolder(), getLogger());
        this.currency = new CurrencyService(accounts, config.getDouble("currency.ore-per-are", 1.0));
        this.market = new MarketManager(getDataFolder(), resources, accounts, getLogger(),
                config.getDouble("market.fee-percent", 0.0));

        this.screens = new Screens(this, accounts, market, resources, currency,
                config.getString("calculator.url", ""));
        this.stands = new StandManager(this, new DemandContent(market, resources, screens));

        // Добытчику, которому склад закрыли новым спросом, пишем в чат — если он в сети.
        market.setPayoutListener(payout -> {
            Player seller = getServer().getPlayer(payout.seller());
            if (seller != null) {
                seller.sendMessage(Component.text("Ваш склад: продано " + payout.amount() + " × ",
                                NamedTextColor.GREEN)
                        .append(Ui.name(payout.material()))
                        .append(Component.text(" за " + MarketManager.money(payout.money()) + " ар.",
                                NamedTextColor.GREEN)));
            }
        });

        var pm = getServer().getPluginManager();
        pm.registerEvents(new JoinListener(accounts), this);
        pm.registerEvents(new CurrencyListener(this, currency, screens), this);
        pm.registerEvents(new SellListener(this, market, screens), this);
        pm.registerEvents(new GuiListener(screens), this);
        pm.registerEvents(stands, this);
        stands.start();

        // Экраны биржи командой «откуда угодно» — только с правом webtrade.command (см. RemoteAccess).
        remote("trade", new ScreenCommand(screens::mainMenu));
        remote("balance", new ScreenCommand(screens::mainMenu));
        remote("deposit", new ScreenCommand(DepositGui::open));
        remote("withdraw", new WithdrawCommand(currency, screens));
        remote("container", new ScreenCommand(screens::containers));
        remote("sell", new ScreenCommand(SellGui::open));
        // Спрос и склад в меню не выведены — они нужны реже, а меню должно оставаться коротким.
        remote("demand", new ScreenCommand(player -> new DemandGui(screens).open(player)));
        remote("stock", new ScreenCommand(player ->
                new StockGui(screens, player.getUniqueId()).open(player)));
        command("tradestand", new StandCommand(stands));
        command("tradecalc", new CalculatorCommand(this, resources));

        exportCalculator(config.getBoolean("calculator.export-on-start", true));

        // Страховка от потери данных при падении сервера.
        getServer().getAsyncScheduler().runAtFixedRate(this, t -> {
            accounts.save();
            market.save();
        }, AUTOSAVE_TICKS * 50, AUTOSAVE_TICKS * 50, java.util.concurrent.TimeUnit.MILLISECONDS);

        getLogger().info("WebTrade включён. Ресурсов в каталоге: " + resources.all().size());
    }

    @Override
    public void onDisable() {
        // Сущности стендов не должны пережить выключение плагина — иначе останутся в мире.
        if (stands != null) {
            stands.stop();
        }
        // Если у кого-то открыто окно депозита — рассчитаться, чтобы руда не пропала.
        if (currency != null) {
            for (Player player : getServer().getOnlinePlayers()) {
                if (player.getOpenInventory().getTopInventory().getHolder() instanceof DepositGui.Holder) {
                    currency.settleDeposit(player, player.getOpenInventory().getTopInventory());
                    player.closeInventory();
                }
            }
        }
        if (market != null) {
            market.save();
        }
        if (accounts != null) {
            accounts.save();
        }
    }

    /**
     * Выгружает калькулятор при старте, чтобы страница не отставала от цен в resources.yml.
     * Вручную то же самое делает /tradecalc.
     */
    private void exportCalculator(boolean enabled) {
        if (!enabled) {
            return;
        }
        LegacyBlocks legacy = new LegacyBlocks(getLogger());
        List<String> ids = resources.all().stream().map(type -> type.material().name()).toList();
        String version = getServer().getMinecraftVersion();
        CalculatorAssets assets = new CalculatorAssets(getDataFolder().toPath(), getLogger());
        calculatorAssets = assets.cached(version, ids);
        writeCalculator(legacy);
        // Значков ещё нет — качаем и рисуем в фоне, потом выгружаем страницу ещё раз.
        if (!calculatorAssets.hasIcons() && getConfig().getBoolean("calculator.icons", true)) {
            getServer().getAsyncScheduler().runNow(this, t -> {
                CalculatorAssets.Result fresh = assets.prepare(version, ids);
                if (fresh.hasIcons() || !fresh.names().isEmpty()) {
                    calculatorAssets = fresh;
                    writeCalculator(legacy);
                }
            });
        }
    }

    /** Пишет calculator.html с тем, что сейчас известно о названиях и значках. */
    public void writeCalculator(LegacyBlocks legacy) {
        try {
            var file = CalculatorExport.write(this, resources, legacy, calculatorAssets);
            getLogger().info("Калькулятор выгружен: " + file.getFileName()
                    + (calculatorAssets.hasIcons() ? " (со значками)" : ""));
        } catch (IOException e) {
            getLogger().warning("Не удалось выгрузить калькулятор: " + e.getMessage());
        }
    }

    public CalculatorAssets.Result calculatorAssets() {
        return calculatorAssets;
    }

    private void remote(String name, org.bukkit.command.CommandExecutor executor) {
        command(name, new RemoteAccess(this, executor));
    }

    private void command(String name, org.bukkit.command.CommandExecutor executor) {
        Objects.requireNonNull(getCommand(name), "Команда " + name + " не объявлена в plugin.yml")
                .setExecutor(executor);
    }

    public AccountManager accounts() {
        return accounts;
    }

    public ResourceRegistry resources() {
        return resources;
    }

    public CurrencyService currency() {
        return currency;
    }

    public MarketManager market() {
        return market;
    }

    public Screens screens() {
        return screens;
    }
}
