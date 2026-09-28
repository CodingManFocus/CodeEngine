package kr.codenamemc.codeengine.runtime;

import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ManagedCallbackTest {
    @Test void commandCloseWaitsUntilTheCurrentCommandReturns() {
        var activity = new EventActivity();
        String[] arguments = {"one", "two"};
        var command = new ScopedCommandExecutor(activity, (sender, source, label, args) -> {
            assertEquals("sample", label);
            assertSame(arguments, args);
            activity.close();
            assertFalse(activity.drained().isDone());
            return true;
        });
        assertTrue(command.onCommand(null, null, "sample", arguments));
        assertTrue(activity.drained().isDone());
        assertFalse(command.onCommand(null, null, "sample", arguments));
    }

    @Test void commandErrorStillReleasesTheCurrentCall() {
        var activity = new EventActivity();
        var command = new ScopedCommandExecutor(activity, (sender, source, label, args) -> {
            activity.close();
            throw new AssertionError("user failure");
        });
        assertThrows(AssertionError.class, () -> command.onCommand(null, null, "sample", new String[0]));
        assertTrue(activity.drained().isDone());
    }

    @Test void timerCloseWaitsUntilTheCurrentTimerReturns() {
        var activity = new EventActivity();
        var calls = new AtomicInteger();
        var task = new ScopedTask(activity, () -> {
            calls.incrementAndGet();
            activity.close();
            assertFalse(activity.drained().isDone());
        });
        task.run();
        assertTrue(activity.drained().isDone());
        task.run();
        assertEquals(1, calls.get());
    }

    @Test void timerErrorStillReleasesTheCurrentCall() {
        var activity = new EventActivity();
        var task = new ScopedTask(activity, () -> {
            activity.close();
            throw new AssertionError("user failure");
        });
        assertThrows(AssertionError.class, task::run);
        assertTrue(activity.drained().isDone());
    }
}
