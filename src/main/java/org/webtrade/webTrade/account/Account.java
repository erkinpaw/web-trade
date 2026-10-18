package org.webtrade.webTrade.account;

import java.util.UUID;

/** Счёт игрока: баланс в арах (дробный). */
public final class Account {

    private final UUID uuid;
    private volatile String name;
    private volatile double balance;

    public Account(UUID uuid, String name, double balance) {
        this.uuid = uuid;
        this.name = name;
        this.balance = balance;
    }

    public UUID uuid() {
        return uuid;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public double balance() {
        return balance;
    }

    public void setBalance(double balance) {
        this.balance = Math.max(0.0, balance);
    }
}
