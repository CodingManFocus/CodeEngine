package kr.codenamemc.codeengine.intelligence;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.io.RandomAccessFile;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import org.eclipse.aether.repository.RemoteRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import static org.junit.jupiter.api.Assertions.*;

class MavenArtifactResolverTest {
    private static final String VERSION = "26.2.build.124-stable";
    @TempDir Path temporary;

    @Test void resolvesManagedTransitiveTypesAndExcludesNonCompileArtifacts() throws Exception {
        Path repository = temporary.resolve("repository");
        artifact(repository, "fixture", "parent", "1", "pom", """
            <dependencyManagement><dependencies>
              <dependency><groupId>fixture</groupId><artifactId>leaf</artifactId><version>2</version></dependency>
            </dependencies></dependencyManagement>
            """);
        artifact(repository, "fixture", "leaf", "2", "jar", "");
        artifact(repository, "fixture", "middle", "1", "jar", """
            <parent><groupId>fixture</groupId><artifactId>parent</artifactId><version>1</version></parent>
            <dependencies><dependency><groupId>fixture</groupId><artifactId>leaf</artifactId></dependency></dependencies>
            """);
        artifact(repository, "fixture", "runtime", "1", "jar", "");
        artifact(repository, "fixture", "optional", "1", "jar", "");
        artifact(repository, "fixture", "test", "1", "jar", "");
        artifact(repository, "io.papermc.paper", "paper-api", VERSION, "jar", """
            <dependencies>
              <dependency><groupId>fixture</groupId><artifactId>middle</artifactId><version>1</version></dependency>
              <dependency><groupId>fixture</groupId><artifactId>runtime</artifactId><version>1</version><scope>runtime</scope></dependency>
              <dependency><groupId>fixture</groupId><artifactId>optional</artifactId><version>1</version><optional>true</optional></dependency>
              <dependency><groupId>fixture</groupId><artifactId>test</artifactId><version>1</version><scope>test</scope></dependency>
            </dependencies>
            """);
        MavenArtifactResolver resolver = resolver(repository);
        List<Path> result = resolver.resolve(VERSION, temporary.resolve("cache"));
        // Optional dependencies declared directly by the root belong to its compile classpath.
        assertEquals(List.of("leaf-2.jar", "middle-1.jar", "optional-1.jar", "paper-api-" + VERSION + ".jar"),
            result.stream().map(path -> path.getFileName().toString()).sorted().toList());
        // The second request must be satisfiable from Maven's cached release artifacts.
        Files.move(repository, temporary.resolve("unavailable"));
        assertEquals(result, resolver.resolve(VERSION, temporary.resolve("cache")));
    }

    @Test void dependencyPomCannotAddAnotherRepository() throws Exception {
        Path allowed = temporary.resolve("allowed");
        Path untrusted = temporary.resolve("untrusted");
        artifact(untrusted, "fixture", "outside", "1", "jar", "");
        artifact(allowed, "io.papermc.paper", "paper-api", VERSION, "jar", """
            <repositories><repository><id>untrusted</id><url>%s</url></repository></repositories>
            <dependencies><dependency><groupId>fixture</groupId><artifactId>outside</artifactId><version>1</version></dependency></dependencies>
            """.formatted(untrusted.toUri()));
        assertThrows(IOException.class, () -> resolver(allowed).resolve(VERSION, temporary.resolve("cache")));
        assertFalse(Files.exists(temporary.resolve("cache/fixture/outside/1/outside-1.jar")));
    }

    @Test void resolvesLegacyTimestampedSnapshotOnlyWithinItsMinecraftVersion() throws Exception {
        Path repository = temporary.resolve("repository");
        String snapshot = "1.21.11-R0.1-SNAPSHOT";
        String timestamped = "1.21.11-R0.1-20260929.130000-4";
        artifact(repository, "io.papermc.paper", "paper-api", snapshot, "jar", "");
        Path directory = repository.resolve("io/papermc/paper/paper-api/" + snapshot);
        for (String suffix : List.of(".pom", ".pom.sha1", ".jar", ".jar.sha1")) {
            Files.move(directory.resolve("paper-api-" + snapshot + suffix),
                directory.resolve("paper-api-" + timestamped + suffix));
        }
        Path metadata = directory.resolve("maven-metadata.xml");
        Files.writeString(metadata, """
            <metadata><groupId>io.papermc.paper</groupId><artifactId>paper-api</artifactId><version>%s</version>
              <versioning><snapshot><timestamp>20260929.130000</timestamp><buildNumber>4</buildNumber></snapshot>
              <lastUpdated>20260929130000</lastUpdated><snapshotVersions>
                <snapshotVersion><extension>pom</extension><value>%s</value><updated>20260929130000</updated></snapshotVersion>
                <snapshotVersion><extension>jar</extension><value>%s</value><updated>20260929130000</updated></snapshotVersion>
              </snapshotVersions></versioning>
            </metadata>
            """.formatted(snapshot, timestamped, timestamped));
        checksum(metadata);
        List<Path> result = resolver(repository).resolve(snapshot, temporary.resolve("cache"));
        assertEquals(1, result.size());
        assertEquals("paper-api-" + snapshot + ".jar", result.getFirst().getFileName().toString());
        assertArrayEquals(Files.readAllBytes(directory.resolve("paper-api-" + timestamped + ".jar")),
            Files.readAllBytes(result.getFirst()));
    }

    @Test void invalidChecksumFailsInsteadOfExposingAnArtifact() throws Exception {
        Path repository = temporary.resolve("repository");
        artifact(repository, "io.papermc.paper", "paper-api", VERSION, "jar", "");
        Path jar = repository.resolve("io/papermc/paper/paper-api/" + VERSION + "/paper-api-" + VERSION + ".jar");
        Files.writeString(jar.resolveSibling(jar.getFileName() + ".sha1"), "0000000000000000000000000000000000000000");
        assertThrows(IOException.class, () -> resolver(repository).resolve(VERSION, temporary.resolve("cache")));
    }

    @Test void interruptedWorkerDoesNotStartFilesystemOrNetworkWork() {
        Path cache = temporary.resolve("cache");
        Thread.currentThread().interrupt();
        try {
            assertThrows(InterruptedIOException.class, () -> resolver(temporary.resolve("missing")).resolve(VERSION, cache));
            assertFalse(Files.exists(cache));
            assertTrue(Thread.currentThread().isInterrupted());
        } finally {
            Thread.interrupted();
        }
    }

    @Test void oversizedArtifactIsCancelledBeforeItIsCached() throws Exception {
        Path repository = temporary.resolve("repository");
        artifact(repository, "io.papermc.paper", "paper-api", VERSION, "jar", "");
        Path jar = repository.resolve("io/papermc/paper/paper-api/" + VERSION + "/paper-api-" + VERSION + ".jar");
        try (RandomAccessFile sparse = new RandomAccessFile(jar.toFile(), "rw")) {
            sparse.setLength(64L * 1024 * 1024 + 1);
        }
        Path cache = temporary.resolve("cache");
        assertThrows(IOException.class, () -> resolver(repository).resolve(VERSION, cache));
        assertFalse(Files.exists(cache.resolve("io/papermc/paper/paper-api/" + VERSION + "/paper-api-" + VERSION + ".jar")));
    }

    private static MavenArtifactResolver resolver(Path repository) {
        return new MavenArtifactResolver(List.of(new RemoteRepository.Builder("fixture", "default", repository.toUri().toString()).build()));
    }

    private static void artifact(Path repository, String group, String name, String version, String packaging,
                                 String body) throws Exception {
        Path directory = repository.resolve(group.replace('.', '/') + "/" + name + "/" + version);
        Files.createDirectories(directory);
        Path pom = directory.resolve(name + "-" + version + ".pom");
        Files.writeString(pom, """
            <project xmlns="http://maven.apache.org/POM/4.0.0">
              <modelVersion>4.0.0</modelVersion><groupId>%s</groupId><artifactId>%s</artifactId><version>%s</version>
              <packaging>%s</packaging>%s
            </project>
            """.formatted(group, name, version, packaging, body));
        checksum(pom);
        if (packaging.equals("jar")) {
            Path jar = directory.resolve(name + "-" + version + ".jar");
            try (JarOutputStream output = new JarOutputStream(Files.newOutputStream(jar))) {
                output.putNextEntry(new JarEntry("fixture.txt"));
                output.write(name.getBytes(StandardCharsets.UTF_8));
                output.closeEntry();
            }
            checksum(jar);
        }
    }

    private static void checksum(Path file) throws Exception {
        String digest = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(Files.readAllBytes(file)));
        Files.writeString(file.resolveSibling(file.getFileName() + ".sha1"), digest);
    }
}
