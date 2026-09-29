package org.bukkit.entity;

import java.util.UUID;
import org.bukkit.command.CommandSender;

public interface Player extends CommandSender {
    UUID getUniqueId();
    double getHealth();
}
