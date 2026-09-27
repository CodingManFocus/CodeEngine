package kr.codenamemc.codeengine.runtime;

import org.bukkit.event.Event;
import org.bukkit.event.EventException;
import org.bukkit.event.Listener;
import org.bukkit.plugin.EventExecutor;

/** Allocated at registration, never per invocation. Forwards the original Paper objects. */
final class ScopedEventExecutor implements EventExecutor {
    private final EventActivity activity;
    private final Listener listener;
    private final EventExecutor executor;
    ScopedEventExecutor(EventActivity activity, Listener listener, EventExecutor executor) {
        this.activity = activity; this.listener = listener; this.executor = executor;
    }
    @Override public void execute(Listener owner, Event event) throws EventException {
        boolean async = event.isAsynchronous();
        if (!activity.enter(async)) return;
        try { executor.execute(listener, event); }
        finally { activity.leave(async); }
    }
}
