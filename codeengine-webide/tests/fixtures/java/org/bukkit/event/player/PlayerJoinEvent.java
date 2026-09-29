package org.bukkit.event.player;

import org.bukkit.entity.Player;

public class PlayerJoinEvent {
    private final Player player;
    public PlayerJoinEvent(Player player) { this.player = player; }
    public Player getPlayer() { return player; }
}
