package org.webtrade.webTrade.account;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.file.YamlConfiguration;

import java.io.File;
import java.io.IOException;
import java.util.Collection;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Хранит счета игроков и персистит их в accounts.yml.
 * Балансовые операции атомарны (synchronized), чтобы обработчики интерфейсов не гонялись.
 */
public final class AccountManager {

    private final File file;
    private final ConcurrentHashMap<UUID, Account> accounts = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, UUID> byName = new ConcurrentHashMap<>();

    public AccountManager(File dataFolder) {
        this.file = new File(dataFolder, "accounts.yml");
        load();
    }

    private void load() {
        if (!file.exists()) {
            return;
        }
        YamlConfiguration yaml = YamlConfiguration.loadConfiguration(file);
        for (String key : yaml.getKeys(false)) {
            try {
                UUID uuid = UUID.fromString(key);
                ConfigurationSection sec = yaml.getConfigurationSection(key);
                if (sec == null) {
                    continue;
                }
                // Поле password из веб-версии просто игнорируем — при следующем save() оно исчезнет.
                String name = sec.getString("name", "");
                Account account = new Account(uuid, name, sec.getDouble("balance", 0.0));
                accounts.put(uuid, account);
                if (!name.isEmpty()) {
                    byName.put(name.toLowerCase(Locale.ROOT), uuid);
                }
            } catch (IllegalArgumentException ignored) {
                // некорректный ключ — пропускаем
            }
        }
    }

    public synchronized void save() {
        YamlConfiguration yaml = new YamlConfiguration();
        for (Account account : accounts.values()) {
            String base = account.uuid().toString();
            yaml.set(base + ".name", account.name());
            yaml.set(base + ".balance", account.balance());
        }
        try {
            yaml.save(file);
        } catch (IOException e) {
            throw new RuntimeException("Не удалось сохранить accounts.yml", e);
        }
    }

    public synchronized Account getOrCreate(UUID uuid, String name) {
        Account account = accounts.computeIfAbsent(uuid, u -> new Account(u, name, 0.0));
        if (name != null && !name.isEmpty()) {
            account.setName(name);
            byName.put(name.toLowerCase(Locale.ROOT), uuid);
        }
        return account;
    }

    public Account get(UUID uuid) {
        return accounts.get(uuid);
    }

    public Account findByName(String name) {
        if (name == null) {
            return null;
        }
        UUID uuid = byName.get(name.toLowerCase(Locale.ROOT));
        return uuid == null ? null : accounts.get(uuid);
    }

    public Collection<Account> all() {
        return accounts.values();
    }

    // ---------- баланс ----------

    public double balance(UUID uuid) {
        Account account = accounts.get(uuid);
        return account == null ? 0.0 : account.balance();
    }

    public synchronized void deposit(UUID uuid, String name, double amount) {
        Account account = getOrCreate(uuid, name);
        account.setBalance(account.balance() + amount);
    }

    /** Списывает amount, если хватает средств. true при успехе. */
    public synchronized boolean withdraw(UUID uuid, double amount) {
        Account account = accounts.get(uuid);
        if (account == null || account.balance() < amount) {
            return false;
        }
        account.setBalance(account.balance() - amount);
        return true;
    }
}
