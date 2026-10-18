package org.webtrade.webTrade.account;

import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;

/** Гарантирует, что у зашедшего игрока есть счёт и актуальное имя. */
public final class JoinListener implements Listener {

    private final AccountManager accounts;

    public JoinListener(AccountManager accounts) {
        this.accounts = accounts;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        accounts.getOrCreate(event.getPlayer().getUniqueId(), event.getPlayer().getName());
    }
}
