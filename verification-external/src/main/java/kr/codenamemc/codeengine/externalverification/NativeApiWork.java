package kr.codenamemc.codeengine.externalverification;

import me.clip.placeholderapi.PlaceholderAPI;
import org.bukkit.OfflinePlayer;

/** The native baseline uses the same calls and input objects as external.ce. */
public final class NativeApiWork {
    private NativeApiWork() { }
    public static int single(int index) {
        return PlaceholderAPI.setPlaceholders((OfflinePlayer) null, BenchmarkRegistry.singleQuery(index)).hashCode();
    }
    public static int multiple(int index) {
        return PlaceholderAPI.setPlaceholders((OfflinePlayer) null, BenchmarkRegistry.multipleQuery(index)).hashCode();
    }
}
