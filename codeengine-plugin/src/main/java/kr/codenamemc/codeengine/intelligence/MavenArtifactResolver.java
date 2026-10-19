package kr.codenamemc.codeengine.intelligence;

import java.io.IOException;
import java.io.InterruptedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import org.apache.maven.repository.internal.MavenRepositorySystemUtils;
import org.eclipse.aether.DefaultRepositorySystemSession;
import org.eclipse.aether.RepositorySystem;
import org.eclipse.aether.artifact.Artifact;
import org.eclipse.aether.artifact.DefaultArtifact;
import org.eclipse.aether.collection.CollectRequest;
import org.eclipse.aether.connector.basic.BasicRepositoryConnectorFactory;
import org.eclipse.aether.graph.Dependency;
import org.eclipse.aether.impl.DefaultServiceLocator;
import org.eclipse.aether.repository.LocalRepository;
import org.eclipse.aether.repository.RemoteRepository;
import org.eclipse.aether.repository.RepositoryPolicy;
import org.eclipse.aether.resolution.ArtifactResult;
import org.eclipse.aether.resolution.DependencyRequest;
import org.eclipse.aether.resolution.DependencyResolutionException;
import org.eclipse.aether.resolution.VersionRangeRequest;
import org.eclipse.aether.resolution.VersionRangeResolutionException;
import org.eclipse.aether.resolution.VersionRangeResult;
import org.eclipse.aether.spi.connector.RepositoryConnectorFactory;
import org.eclipse.aether.spi.connector.transport.TransporterFactory;
import org.eclipse.aether.transfer.AbstractTransferListener;
import org.eclipse.aether.transfer.TransferCancelledException;
import org.eclipse.aether.transfer.TransferEvent;
import org.eclipse.aether.transport.file.FileTransporterFactory;
import org.eclipse.aether.transport.http.HttpTransporterFactory;
import org.eclipse.aether.util.artifact.JavaScopes;
import org.eclipse.aether.util.filter.DependencyFilterUtils;
import org.eclipse.aether.version.Version;

/** Downloads opaque Maven artifacts. It never loads classes or builds an IDE type index. */
public final class MavenArtifactResolver {
    private static final int MAX_ARTIFACTS = 96;
    private static final long MAX_ARTIFACT_BYTES = 64L * 1024 * 1024;
    private static final long MAX_TRANSFER_BYTES = 192L * 1024 * 1024;
    private static final String PAPER_API = "io.papermc.paper:paper-api";
    private static final List<RemoteRepository> REPOSITORIES = List.of(
        new RemoteRepository.Builder("papermc", "default", "https://repo.papermc.io/repository/maven-public/").build(),
        new RemoteRepository.Builder("central", "default", "https://maven-central.storage-download.googleapis.com/maven2/").build());

    private final List<RemoteRepository> repositories;

    public MavenArtifactResolver() {
        this(REPOSITORIES);
    }

    /** Separate repository injection keeps tests entirely local; production repositories are fixed. */
    MavenArtifactResolver(List<RemoteRepository> repositories) {
        this.repositories = List.copyOf(repositories);
    }

    /** Must be called on the dedicated background worker, never on a server tick. */
    public List<Path> resolve(String version, Path cacheDirectory) throws IOException {
        return resolve(version, cacheDirectory, true);
    }

    /** Paper API's transitive dependencies only: a fork supplies its own API JAR. */
    public List<Path> resolveDependencies(String version, Path cacheDirectory) throws IOException {
        return resolve(version, cacheDirectory, false);
    }

    /** Published paper-api versions from repository metadata; callers filter them by Minecraft version. */
    public List<String> versions(Path cacheDirectory) throws IOException {
        checkInterrupted();
        Files.createDirectories(cacheDirectory);
        RepositorySystem system = newRepositorySystem();
        try {
            VersionRangeResult result = system.resolveVersionRange(newSession(system, cacheDirectory),
                new VersionRangeRequest(new DefaultArtifact(PAPER_API + ":jar:[0,)"), repositories, null));
            checkInterrupted();
            if (result.getVersions().isEmpty() && !result.getExceptions().isEmpty()) {
                Exception cause = result.getExceptions().getFirst();
                throw new IOException("Could not list published Paper API versions: " + cause.getMessage(), cause);
            }
            return result.getVersions().stream().map(Version::toString).toList();
        } catch (VersionRangeResolutionException exception) {
            checkInterrupted();
            throw new IOException("Could not list published Paper API versions: " + exception.getMessage(), exception);
        } finally {
            system.shutdown();
        }
    }

    private List<Path> resolve(String version, Path cacheDirectory, boolean includeApi) throws IOException {
        // Validate before interpreting the caller's value as a Maven coordinate or cache path.
        new PaperApiVersion(version, "validation", "validation");
        checkInterrupted();
        Files.createDirectories(cacheDirectory);
        RepositorySystem system = newRepositorySystem();
        try {
            CollectRequest collect = new CollectRequest(
                new Dependency(new DefaultArtifact(PAPER_API + ":jar:" + version), JavaScopes.COMPILE),
                repositories);
            DependencyRequest request = new DependencyRequest(collect,
                DependencyFilterUtils.classpathFilter(JavaScopes.COMPILE));
            List<ArtifactResult> resolved = system.resolveDependencies(newSession(system, cacheDirectory), request)
                .getArtifactResults();
            checkInterrupted();
            List<Path> paths = new ArrayList<>();
            Set<Path> seen = new HashSet<>();
            long totalBytes = 0;
            for (ArtifactResult result : resolved) {
                checkInterrupted();
                Artifact artifact = result.getArtifact();
                if (!"jar".equals(artifact.getExtension())) continue;
                if (!includeApi && PAPER_API.equals(artifact.getGroupId() + ":" + artifact.getArtifactId())) continue;
                Path path = artifact.getFile().toPath().toRealPath();
                if (!seen.add(path)) continue;
                long bytes = Files.size(path);
                totalBytes += bytes;
                if (paths.size() >= MAX_ARTIFACTS || bytes > MAX_ARTIFACT_BYTES || totalBytes > MAX_TRANSFER_BYTES) {
                    throw new IOException("Paper API dependency set exceeds its artifact or size limit.");
                }
                paths.add(path);
            }
            if (includeApi && paths.isEmpty()) throw new IOException("Paper API resolution returned no JAR files.");
            return List.copyOf(paths);
        } catch (DependencyResolutionException exception) {
            checkInterrupted();
            throw new IOException("Could not download Paper API " + version + " and its dependencies: "
                + exception.getMessage(), exception);
        } finally {
            system.shutdown();
        }
    }

    private static DefaultRepositorySystemSession newSession(RepositorySystem system, Path cacheDirectory) {
        DefaultRepositorySystemSession session = MavenRepositorySystemUtils.newSession();
        session.setLocalRepositoryManager(system.newLocalRepositoryManager(session,
            new LocalRepository(cacheDirectory.toFile())));
        session.setIgnoreArtifactDescriptorRepositories(true);
        session.setChecksumPolicy(RepositoryPolicy.CHECKSUM_POLICY_FAIL);
        session.setUpdatePolicy(RepositoryPolicy.UPDATE_POLICY_DAILY);
        session.setConfigProperty("aether.connector.connectTimeout", 15_000);
        session.setConfigProperty("aether.connector.requestTimeout", 30_000);
        session.setConfigProperty("aether.connector.http.retryHandler.count", 0);
        // Keep transfers on the worker: cancellation and transfer limits have one owner.
        session.setConfigProperty("aether.connector.basic.threads", 1);
        session.setConfigProperty("aether.metadataResolver.threads", 1);
        session.setConfigProperty("aether.dependencyCollector.impl", "df");
        session.setTransferListener(new TransferLimits(Thread.currentThread()));
        session.setReadOnly();
        return session;
    }

    @SuppressWarnings("deprecation")
    private static RepositorySystem newRepositorySystem() throws IOException {
        DefaultServiceLocator locator = MavenRepositorySystemUtils.newServiceLocator();
        locator.addService(RepositoryConnectorFactory.class, BasicRepositoryConnectorFactory.class);
        locator.addService(TransporterFactory.class, FileTransporterFactory.class);
        locator.addService(TransporterFactory.class, HttpTransporterFactory.class);
        List<Throwable> failures = new ArrayList<>();
        locator.setErrorHandler(new DefaultServiceLocator.ErrorHandler() {
            @Override public void serviceCreationFailed(Class<?> type, Class<?> implementation, Throwable exception) {
                failures.add(exception);
            }
        });
        RepositorySystem system = locator.getService(RepositorySystem.class);
        if (system == null) {
            IOException failure = new IOException("Could not initialize the bundled Maven resolver.");
            failures.forEach(failure::addSuppressed);
            throw failure;
        }
        return system;
    }

    private static void checkInterrupted() throws InterruptedIOException {
        if (Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Paper API download was cancelled.");
    }

    private static final class TransferLimits extends AbstractTransferListener {
        private final Thread owner;
        private final AtomicLong transferred = new AtomicLong();
        private final Set<String> jars = new HashSet<>();

        TransferLimits(Thread owner) {
            this.owner = owner;
        }

        @Override public synchronized void transferInitiated(TransferEvent event) throws TransferCancelledException {
            checkCancelled();
            String name = event.getResource().getResourceName();
            if (name.endsWith(".jar") && jars.add(name) && jars.size() > MAX_ARTIFACTS) {
                throw new TransferCancelledException("Too many Paper API dependencies.");
            }
        }

        @Override public void transferStarted(TransferEvent event) throws TransferCancelledException {
            checkCancelled();
            if (event.getResource().getContentLength() > MAX_ARTIFACT_BYTES) {
                throw new TransferCancelledException("Paper API artifact exceeds the download size limit.");
            }
        }

        @Override public void transferProgressed(TransferEvent event) throws TransferCancelledException {
            checkCancelled();
            long total = transferred.addAndGet(event.getDataLength());
            if (event.getTransferredBytes() > MAX_ARTIFACT_BYTES || total > MAX_TRANSFER_BYTES) {
                throw new TransferCancelledException("Paper API download exceeds its size limit.");
            }
        }

        private void checkCancelled() throws TransferCancelledException {
            if (owner.isInterrupted() || Thread.currentThread().isInterrupted()) {
                throw new TransferCancelledException("Paper API download was cancelled.");
            }
        }
    }
}
