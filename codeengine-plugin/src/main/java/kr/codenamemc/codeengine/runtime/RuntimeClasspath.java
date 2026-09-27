package kr.codenamemc.codeengine.runtime;

import java.io.File;
import java.net.*;
import java.nio.file.*;
import java.util.*;

/** Collects actual loader URLs; no network dependency resolution at server startup. */
public final class RuntimeClasspath {
    private RuntimeClasspath() { }
    public static String collect(Class<?>... anchors) throws Exception {
        Set<Path> entries = new LinkedHashSet<>();
        for (String path : System.getProperty("java.class.path", "").split(File.pathSeparator)) {
            if (!path.isBlank()) entries.add(Path.of(path).toAbsolutePath());
        }
        for (Class<?> anchor : anchors) {
            var domain = anchor.getProtectionDomain();
            if (domain != null && domain.getCodeSource() != null) add(entries, domain.getCodeSource().getLocation());
            for (ClassLoader loader = anchor.getClassLoader(); loader != null; loader = loader.getParent()) {
                if (loader instanceof URLClassLoader urls) for (URL url : urls.getURLs()) add(entries, url);
            }
        }
        return entries.stream().filter(Files::exists).map(Path::toString).collect(java.util.stream.Collectors.joining(File.pathSeparator));
    }
    private static void add(Set<Path> entries, URL url) throws URISyntaxException {
        if (url.getProtocol().equals("file")) entries.add(Path.of(url.toURI()).toAbsolutePath());
    }
}
