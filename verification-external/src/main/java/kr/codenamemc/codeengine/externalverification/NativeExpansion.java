package kr.codenamemc.codeengine.externalverification;

import me.clip.placeholderapi.expansion.PlaceholderExpansion;
import org.bukkit.OfflinePlayer;

/** Deterministic callback through the real PlaceholderAPI parser and expansion registry. */
public final class NativeExpansion extends PlaceholderExpansion {
    @Override public String getIdentifier() { return "cenative"; }
    @Override public String getAuthor() { return "CodeEngine verification"; }
    @Override public String getVersion() { return "1.0"; }
    @Override public boolean persist() { return true; }
    @Override public String onRequest(OfflinePlayer player, String parameters) {
        return "value:" + parameters;
    }
}
