package kr.codenamemc.codeengine.intelligence;

import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A published API coordinate, never an unconstrained latest-version lookup. */
public record PaperApiVersion(String version, String compatibility, String detail) {
    private static final Pattern MINECRAFT_VERSION = Pattern.compile("[0-9]+(?:\\.[0-9]+){1,2}");
    private static final Pattern BUILD_VERSION = Pattern.compile(
        "([0-9]+(?:\\.[0-9]+){1,2})\\.build\\.([0-9]+)-(alpha|beta|rc|stable)");
    private static final Pattern LEGACY_VERSION = Pattern.compile("(1\\.[0-9]+(?:\\.[0-9]+)?)-R0\\.1-SNAPSHOT");

    public PaperApiVersion {
        Objects.requireNonNull(version, "version");
        Objects.requireNonNull(compatibility, "compatibility");
        Objects.requireNonNull(detail, "detail");
        if (!BUILD_VERSION.matcher(version).matches() && !LEGACY_VERSION.matcher(version).matches()) {
            throw new IllegalArgumentException("Unsupported Paper API version: " + version);
        }
    }

    public static PaperApiVersion from(String bukkitVersion, String minecraftVersion, String serverVersion) {
        Objects.requireNonNull(bukkitVersion, "bukkitVersion");
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        if (!MINECRAFT_VERSION.matcher(minecraftVersion).matches()) {
            throw new IllegalArgumentException("Unsupported Minecraft version: " + minecraftVersion);
        }
        Matcher build = BUILD_VERSION.matcher(bukkitVersion);
        if (build.matches() && build.group(1).equals(minecraftVersion)) {
            return exact(bukkitVersion);
        }
        Matcher legacy = LEGACY_VERSION.matcher(bukkitVersion);
        if (legacy.matches() && legacy.group(1).equals(minecraftVersion)) {
            return new PaperApiVersion(bukkitVersion, "compatible-snapshot",
                "The API matches Minecraft " + minecraftVersion
                    + "; legacy Paper snapshots cannot identify the running server's exact build.");
        }
        // A server may expose its complete published coordinate in its description.
        // A bare server build number cannot reveal the Maven release channel.
        if (serverVersion != null && !build.matches() && !legacy.matches()) {
            Matcher explicit = BUILD_VERSION.matcher(serverVersion);
            if (explicit.find() && explicit.group(1).equals(minecraftVersion)) {
                String coordinate = explicit.group();
                if (!explicit.find()) return exact(coordinate);
            }
        }
        throw new IllegalArgumentException("Cannot identify a matching published Paper API from Bukkit version "
            + bukkitVersion + " for Minecraft " + minecraftVersion + ".");
    }

    private static PaperApiVersion exact(String version) {
        return new PaperApiVersion(version, "exact", "The API uses the server's published Paper build coordinate.");
    }
}
