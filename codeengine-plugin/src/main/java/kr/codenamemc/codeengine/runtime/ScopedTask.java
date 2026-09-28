package kr.codenamemc.codeengine.runtime;

/** Allocated once at registration; keeps reentrant unload from closing an executing timer. */
final class ScopedTask implements Runnable {
    private final EventActivity activity;
    private final Runnable action;

    ScopedTask(EventActivity activity, Runnable action) {
        this.activity = activity;
        this.action = action;
    }

    @Override public void run() {
        if (!activity.enter(false)) return;
        try { action.run(); }
        finally { activity.leave(false); }
    }
}
