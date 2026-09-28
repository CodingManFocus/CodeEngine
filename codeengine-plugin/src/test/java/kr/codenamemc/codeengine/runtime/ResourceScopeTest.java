package kr.codenamemc.codeengine.runtime;

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ResourceScopeTest {
    @Test void closesEachRegistrationOnceInReverseOrder() {
        var scope = new ResourceScope();
        var calls = new ArrayList<Integer>();
        scope.add(() -> calls.add(1));
        scope.add(() -> calls.add(2));
        scope.add(() -> calls.add(3));
        assertNull(scope.close());
        assertNull(scope.close());
        assertEquals(List.of(3, 2, 1), calls);
    }

    @Test void attemptsAllResourcesAndAggregatesFailuresInCloseOrder() {
        var scope = new ResourceScope();
        var calls = new ArrayList<Integer>();
        var first = new AssertionError("first");
        var second = new IOException("second");
        scope.add(() -> { calls.add(1); throw second; });
        scope.add(() -> calls.add(2));
        scope.add(() -> { calls.add(3); throw first; });
        assertSame(first, scope.close());
        assertArrayEquals(new Throwable[]{second}, first.getSuppressed());
        assertEquals(List.of(3, 2, 1), calls);
        assertNull(scope.close());
    }

    @Test void repeatedFailureInstanceDoesNotInterruptCleanup() {
        var scope = new ResourceScope();
        var shared = new IOException("same failure");
        scope.add(() -> { throw shared; });
        scope.add(() -> { throw shared; });
        assertSame(shared, scope.close());
        assertEquals(0, shared.getSuppressed().length);
    }

    @Test void reentrantCloseDoesNotRunRemainingResourcesEarly() {
        var scope = new ResourceScope();
        var calls = new ArrayList<Integer>();
        scope.add(() -> calls.add(1));
        scope.add(() -> {
            calls.add(2);
            assertNull(scope.close());
            calls.add(3);
        });
        assertNull(scope.close());
        assertEquals(List.of(2, 3, 1), calls);
    }

    @Test void rejectsNullAndRegistrationsDuringOrAfterCleanup() {
        var scope = new ResourceScope();
        assertThrows(NullPointerException.class, () -> scope.add(null));
        scope.add(() -> assertThrows(IllegalStateException.class, () -> scope.add(() -> { })));
        assertNull(scope.close());
        assertThrows(IllegalStateException.class, () -> scope.add(() -> { }));
    }

    @Test void discardingResourcesNeverInvokesUserCode() {
        var scope = new ResourceScope();
        scope.add(() -> fail("Discarded cleanup must never be called"));
        scope.discard();
        scope.discard();
        assertNull(scope.close());
        assertThrows(IllegalStateException.class, () -> scope.add(() -> { }));
    }

    @Test void stoppedEngineSkipsAndDiscardsAllCleanup() {
        var scope = new ResourceScope();
        scope.add(() -> fail("Unavailable provider must not be called"));
        assertInstanceOf(IllegalStateException.class, scope.close(() -> false));
        assertNull(scope.close());
    }

    @Test void engineStoppedByCleanupSkipsRemainingCallbacks() {
        var scope = new ResourceScope();
        var available = new AtomicBoolean(true);
        scope.add(() -> fail("Cleanup must not run after provider teardown"));
        scope.add(() -> available.set(false));
        assertInstanceOf(IllegalStateException.class, scope.close(available::get));
        assertNull(scope.close());
    }

    @Test void guardFailureDiscardsRemainingCallbacksAndPreservesEarlierFailure() {
        var scope = new ResourceScope();
        var available = new AtomicBoolean(true);
        var closeFailure = new IOException("close failed");
        var guardFailure = new IllegalStateException("dependency check failed");
        scope.add(() -> fail("Unverified cleanup must not run"));
        scope.add(() -> {
            available.set(false);
            throw closeFailure;
        });
        assertSame(closeFailure, scope.close(() -> {
            if (!available.get()) throw guardFailure;
            return true;
        }));
        assertArrayEquals(new Throwable[]{guardFailure}, closeFailure.getSuppressed());
        assertNull(scope.close());
    }
    @Test void stoppedProviderDoesNotSuppressLocalOrOtherProviderCleanup() {
        var scope = new ResourceScope();
        var calls = new ArrayList<String>();
        var available = new AtomicBoolean(true);
        scope.add(() -> calls.add("local"));
        scope.addForProvider(() -> true, () -> calls.add("otherProvider"));
        scope.addForProvider(available::get, () -> fail("stopped provider called"));
        scope.add(() -> available.set(false));
        assertNull(scope.close());
        assertEquals(List.of("otherProvider", "local"), calls);
        assertNull(scope.close());
    }

    @Test void providerFailureDoesNotPreventIndependentCleanup() {
        var scope = new ResourceScope();
        var calls = new ArrayList<String>();
        var failure = new IOException("provider failure");
        scope.add(() -> calls.add("saved"));
        scope.addForProvider(() -> true, () -> { throw failure; });
        assertSame(failure, scope.close());
        assertEquals(List.of("saved"), calls);
    }

    @Test void brokenProviderGuardDoesNotPreventIndependentCleanup() {
        var scope = new ResourceScope();
        var calls = new ArrayList<String>();
        var failure = new IllegalStateException("guard failure");
        scope.add(() -> calls.add("saved"));
        scope.addForProvider(() -> { throw failure; }, () -> fail("unverified provider called"));
        assertSame(failure, scope.close());
        assertEquals(List.of("saved"), calls);
    }
}
