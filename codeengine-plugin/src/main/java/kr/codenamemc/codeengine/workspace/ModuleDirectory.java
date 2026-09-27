package kr.codenamemc.codeengine.workspace;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;

/** Prepares the module source directory before any example creation or loading. */
public final class ModuleDirectory {
    private ModuleDirectory() { }

    public static Path prepare(Path dataDirectory) throws IOException {
        Path modules = dataDirectory.resolve("modules");
        // Only the upgrade boundary retains the old on-disk name.
        Path legacyDirectory = dataDirectory.resolve("scripts");
        if (Files.isSymbolicLink(modules) || Files.isSymbolicLink(legacyDirectory)) {
            throw new IOException("Module source directories cannot be symlinks");
        }
        if (Files.exists(legacyDirectory, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isDirectory(legacyDirectory, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Legacy module source path is not a directory: " + legacyDirectory);
            }
            if (Files.exists(modules, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("Both module source directories exist: " + legacyDirectory + " and " + modules
                    + ". Merge their contents into modules and remove the legacy directory before restarting."
                    + " No files were moved or overwritten.");
            }
            // No REPLACE_EXISTING: never overwrite a destination created during startup.
            Files.move(legacyDirectory, modules);
        }
        Files.createDirectories(modules);
        return modules;
    }
}
