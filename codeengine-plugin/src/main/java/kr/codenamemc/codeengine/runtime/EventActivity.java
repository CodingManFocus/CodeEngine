package kr.codenamemc.codeengine.runtime;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/** Main-thread lifecycle; atomic admission for asynchronous callbacks. One-way close. */
final class EventActivity {
    private static final int closedBit = Integer.MIN_VALUE;
    private final AtomicInteger asynchronous = new AtomicInteger();
    private final CompletableFuture<Void> drained = new CompletableFuture<>();
    // Only the server thread writes this count. Async exits may read it during close.
    private volatile int mainCalls;

    boolean enter(boolean async) {
        if (!async) {
            if (asynchronous.get() < 0) return false;
            mainCalls++;
            return true;
        }
        while (true) {
            int current = asynchronous.get();
            if (current < 0) return false;
            if (current == Integer.MAX_VALUE) throw new IllegalStateException("Too many asynchronous event calls");
            if (asynchronous.compareAndSet(current, current + 1)) return true;
        }
    }
    void leave(boolean async) {
        if (async) asynchronous.decrementAndGet();
        else mainCalls--;
        completeIfDrained();
    }
    void close() {
        // Called on the server thread, serialized with synchronous event entry.
        asynchronous.getAndUpdate(value -> value | closedBit);
        completeIfDrained();
    }
    CompletableFuture<Void> drained() { return drained; }
    private void completeIfDrained() {
        if (asynchronous.get() == closedBit && mainCalls == 0) drained.complete(null);
    }
}
