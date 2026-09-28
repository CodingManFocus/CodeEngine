package kr.codenamemc.codeengine.runtime.dependency;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Path;

/** Canonical defining artifact, shared by compile-time selection and runtime linking. */
final class ClassOrigin {
    private ClassOrigin() { }
    static Path path(Class<?> type) {
        var domain = type.getProtectionDomain();
        var source = domain == null ? null : domain.getCodeSource();
        if (source == null || source.getLocation() == null || !source.getLocation().getProtocol().equals("file")) return null;
        try { return Path.of(source.getLocation().toURI()).toRealPath(); }
        catch (IOException | URISyntaxException | IllegalArgumentException ignored) { return null; }
    }
}
