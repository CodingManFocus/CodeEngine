package kr.codenamemc.codeengine.intelligence;

import java.util.Collection;
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

    /** Forks (Purpur, Folia, ...) keep Paper's version format but number builds independently. */
    public static boolean isPaper(String serverName) {
        return "Paper".equalsIgnoreCase(serverName);
    }

    public static boolean isLegacy(String bukkitVersion) {
        return bukkitVersion != null && LEGACY_VERSION.matcher(bukkitVersion).matches();
    }

    public static PaperApiVersion from(String bukkitVersion, String minecraftVersion, String serverVersion) {
        Objects.requireNonNull(bukkitVersion, "bukkitVersion");
        requireMinecraftVersion(minecraftVersion);
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

    /**
     * A fork's build number is not a Paper build, so only its Minecraft version selects the Paper
     * base: the same legacy snapshot, or the newest published Paper build of that Minecraft version.
     */
    public static PaperApiVersion forFork(String bukkitVersion, String minecraftVersion, Collection<String> published) {
        Objects.requireNonNull(bukkitVersion, "bukkitVersion");
        Objects.requireNonNull(published, "published");
        requireMinecraftVersion(minecraftVersion);
        Matcher legacy = LEGACY_VERSION.matcher(bukkitVersion);
        if (legacy.matches()) {
            if (legacy.group(1).equals(minecraftVersion)) return fork(bukkitVersion, minecraftVersion);
        } else {
            String newest = null;
            long newestBuild = -1;
            for (String candidate : published) {
                Matcher build = BUILD_VERSION.matcher(candidate);
                if (!build.matches() || !build.group(1).equals(minecraftVersion)) continue;
                long number;
                try { number = Long.parseLong(build.group(2)); } catch (NumberFormatException ignored) { continue; }
                if (number > newestBuild) { newestBuild = number; newest = candidate; }
            }
            if (newest != null) return fork(newest, minecraftVersion);
        }
        throw new IllegalArgumentException("No published Paper API for Minecraft " + minecraftVersion
            + " is available as the base of this Paper fork (Bukkit version " + bukkitVersion + ").");
    }

    private static void requireMinecraftVersion(String minecraftVersion) {
        Objects.requireNonNull(minecraftVersion, "minecraftVersion");
        if (!MINECRAFT_VERSION.matcher(minecraftVersion).matches()) {
            throw new IllegalArgumentException("Unsupported Minecraft version: " + minecraftVersion);
        }
    }

    private static PaperApiVersion exact(String version) {
        return new PaperApiVersion(version, "exact", "The API uses the server's published Paper build coordinate.");
    }

    private static PaperApiVersion fork(String version, String minecraftVersion) {
        return new PaperApiVersion(version, "fork", "Paper API " + version + " is the published base for Minecraft "
            + minecraftVersion + "; it is not the fork's own build.");
    }
}
