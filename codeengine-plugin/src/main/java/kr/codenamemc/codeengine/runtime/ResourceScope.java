package kr.codenamemc.codeengine.runtime;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Objects;
import java.util.function.BooleanSupplier;

/** Main-thread owned cleanup stack; each registration is attempted at most once. */
final class ResourceScope {
    private final Deque<AutoCloseable> resources = new ArrayDeque<>();
    private boolean closed;

    void add(AutoCloseable resource) {
        if (closed) throw new IllegalStateException("Resource scope is closed");
        resources.addLast(Objects.requireNonNull(resource, "resource"));
    }

    /** Returns the first failure with subsequent failures suppressed, after attempting every resource. */
    Throwable close() {
        return close(() -> true);
    }

    /** Stops before the next user callback if its provider is no longer available. */
    Throwable close(BooleanSupplier cleanupAllowed) {
        Objects.requireNonNull(cleanupAllowed, "cleanupAllowed");
        if (closed) return null;
        closed = true;
        Throwable failure = null;
        while (!resources.isEmpty()) {
            try {
                if (!cleanupAllowed.getAsBoolean()) {
                    failure = combine(failure, new IllegalStateException("Dependency stopped; remaining registered resource cleanup skipped"));
                    resources.clear();
                    break;
                }
            } catch (Throwable error) {
                failure = combine(failure, error);
                resources.clear();
                break;
            }
            AutoCloseable resource = resources.removeLast();
            try { resource.close(); }
            catch (Throwable error) { failure = combine(failure, error); }
        }
        return failure;
    }

    void discard() {
        closed = true;
        resources.clear();
    }

    private static Throwable combine(Throwable first, Throwable next) {
        if (first == null) return next;
        if (first != next) first.addSuppressed(next);
        return first;
    }
}
