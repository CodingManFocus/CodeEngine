package kr.codenamemc.codeengine.web;
import java.util.*;
import java.util.concurrent.CompletableFuture;

final class JobRegistry {
    private final Map<String, Job> jobs = new LinkedHashMap<>();
    synchronized String track(CompletableFuture<String> future) {
        if (jobs.size() >= 128) {
            var iterator = jobs.entrySet().iterator();
            while (iterator.hasNext() && jobs.size() >= 128) if (!iterator.next().getValue().status().equals("running")) iterator.remove();
        }
        if (jobs.size() >= 128) throw new IllegalStateException("Too many running jobs");
        String id = UUID.randomUUID().toString();
        jobs.put(id, new Job("running", "Queued"));
        future.whenComplete((value, error) -> finish(id, value, error));
        return id;
    }
    private synchronized void finish(String id, String value, Throwable error) {
        jobs.put(id, new Job(error == null ? "success" : "error", error == null ? value : message(error)));
    }
    synchronized Job get(String id) { return jobs.get(id); }
    private static String message(Throwable error) {
        while (error.getCause() != null && error instanceof java.util.concurrent.CompletionException) error = error.getCause();
        String result = error.getMessage(); return result == null ? error.getClass().getSimpleName() : result;
    }
    record Job(String status, String message) { }
}
