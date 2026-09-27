package kr.codenamemc.codeengine.runtime;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.bukkit.event.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class EventActivityTest {
    @Test void closesAdmissionAndWaitsForAllAdmittedCalls() {
        var activity = new EventActivity();
        assertTrue(activity.enter(true)); assertTrue(activity.enter(true)); assertTrue(activity.enter(false));
        activity.close();
        assertFalse(activity.enter(true)); assertFalse(activity.enter(false)); assertFalse(activity.drained().isDone());
        activity.leave(true); activity.leave(false); assertFalse(activity.drained().isDone());
        activity.leave(true); assertTrue(activity.drained().isDone());
        activity.close(); assertFalse(activity.enter(true));
    }
    @Test void mainThreadReentrantCloseWaitsForCurrentCallback() {
        var activity = new EventActivity();
        assertTrue(activity.enter(false)); activity.close();
        assertFalse(activity.drained().isDone());
        activity.leave(false); assertTrue(activity.drained().isDone());
    }
    @Test void noCallbacksDrainsImmediately() {
        var activity = new EventActivity();
        activity.close(); assertTrue(activity.drained().isDone());
    }
    @Test void closeRacingWithAsyncAdmissionNeverDrainsWhileAdmittedCodeRuns() throws Exception {
        try (var pool = Executors.newFixedThreadPool(4)) {
            for (int repetition = 0; repetition < 100; repetition++) {
                var activity = new EventActivity(); var start = new CountDownLatch(1); var release = new CountDownLatch(1);
                List<Future<?>> calls = new ArrayList<>();
                for (int thread = 0; thread < 4; thread++) calls.add(pool.submit(() -> {
                    try {
                        start.await();
                        if (activity.enter(true)) {
                            try {
                                assertFalse(activity.drained().isDone());
                                assertTrue(release.await(5, TimeUnit.SECONDS));
                                assertFalse(activity.drained().isDone());
                            } finally { activity.leave(true); }
                        }
                    } catch (InterruptedException e) { throw new AssertionError(e); }
                }));
                start.countDown(); activity.close();
                assertFalse(activity.enter(true)); release.countDown();
                for (Future<?> call : calls) call.get(5, TimeUnit.SECONDS);
                activity.drained().get(5, TimeUnit.SECONDS);
            }
        }
    }
    @Test void executorForwardsOriginalObjectsAndReleasesAfterError() {
        var activity = new EventActivity(); Listener original = new Listener() { };
        var event = new ProbeEvent(true);
        var executor = new ScopedEventExecutor(activity, original, (listener, fired) -> {
            assertSame(original, listener); assertSame(event, fired);
            activity.close(); assertFalse(activity.drained().isDone());
            throw new AssertionError("user failure");
        });
        assertThrows(AssertionError.class, () -> executor.execute(new Listener() { }, event));
        assertTrue(activity.drained().isDone());
    }
    @Test void cachedExecutorCannotEnterAfterUnregistrationFence() throws Exception {
        var activity = new EventActivity(); var calls = new AtomicInteger();
        var executor = new ScopedEventExecutor(activity, new Listener() { }, (listener, event) -> calls.incrementAndGet());
        activity.close();
        executor.execute(null, new ProbeEvent(true)); executor.execute(null, new ProbeEvent(false));
        assertEquals(0, calls.get());
    }
    private static final class ProbeEvent extends Event {
        private static final HandlerList handlers = new HandlerList();
        ProbeEvent(boolean async) { super(async); }
        @Override public HandlerList getHandlers() { return handlers; }
    }
}
