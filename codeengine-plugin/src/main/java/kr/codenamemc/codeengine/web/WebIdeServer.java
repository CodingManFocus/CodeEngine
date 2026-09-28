package kr.codenamemc.codeengine.web;

import com.google.gson.Gson;
import com.sun.net.httpserver.*;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.NoSuchFileException;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import kr.codenamemc.codeengine.workspace.ModuleSourceStore;

/** Loopback-only authenticated editor. No CDN, cookies, CORS or token persistence. */
public final class WebIdeServer implements AutoCloseable {
    private final ModuleSourceStore store;
    private final BiFunction<String, String, CompletableFuture<String>> operations;
    private final Supplier<Set<String>> loadedIds;
    private final HttpServer server;
    private final ThreadPoolExecutor pool;
    private final WebSecurity security = new WebSecurity();
    private final JobRegistry jobs = new JobRegistry();
    private final Gson json = new Gson();
    private final java.security.SecureRandom random = new java.security.SecureRandom();
    public WebIdeServer(ModuleSourceStore store, BiFunction<String, String, CompletableFuture<String>> operations,
                        Supplier<Set<String>> loadedIds, int port) throws IOException {
        this.store = store; this.operations = operations; this.loadedIds = loadedIds;
        server = HttpServer.create(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), port), 16);
        pool = new ThreadPoolExecutor(2, 4, 30, TimeUnit.SECONDS, new ArrayBlockingQueue<>(32),
            task -> { Thread t = new Thread(task, "CodeEngine-WebIDE"); t.setDaemon(true); return t; }, new ThreadPoolExecutor.CallerRunsPolicy());
        server.setExecutor(pool); server.createContext("/", this::handle); server.start();
    }
    public String url() { return "http://127.0.0.1:" + server.getAddress().getPort() + "/#" + security.token(); }
    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
            exchange.getResponseHeaders().set("Referrer-Policy", "no-referrer");
            byte[] nonceBytes = new byte[18]; random.nextBytes(nonceBytes);
            String nonce = Base64.getEncoder().encodeToString(nonceBytes);
            exchange.getResponseHeaders().set("Content-Security-Policy", "default-src 'self'; script-src 'self'; style-src 'self' 'nonce-" + nonce + "'; style-src-attr 'unsafe-inline'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'none'");
            if (!security.validHost(exchange)) { send(exchange, 403, Map.of("error", "Invalid host")); return; }
            String path = exchange.getRequestURI().getPath();
            if (!path.startsWith("/api/")) { staticFile(exchange, path, nonce); return; }
            if (!security.authorize(exchange)) { send(exchange, 401, Map.of("error", "Session expired or unauthorized")); return; }
            try { api(exchange, path, query(exchange.getRequestURI().getRawQuery())); }
            catch (ModuleSourceStore.ConflictException e) { send(exchange, 409, Map.of("error", e.getMessage())); }
            catch (NoSuchFileException e) { send(exchange, 404, Map.of("error", "File not found")); }
            catch (IllegalArgumentException e) { send(exchange, 400, Map.of("error", String.valueOf(e.getMessage()))); }
            catch (IllegalStateException e) { send(exchange, 409, Map.of("error", String.valueOf(e.getMessage()))); }
            catch (IOException e) { send(exchange, 400, Map.of("error", String.valueOf(e.getMessage()))); }
            catch (Exception e) { send(exchange, 500, Map.of("error", "Operation failed")); }
        }
    }
    private void api(HttpExchange exchange, String path, Map<String, String> query) throws IOException {
        String method = exchange.getRequestMethod(), id = query.get("id");
        switch (method + " " + path) {
            case "GET /api/modules" -> send(exchange, 200, Map.of("modules", store.list(), "loaded", loadedIds.get()));
            case "GET /api/file" -> {
                var snapshot = store.read(id);
                exchange.getResponseHeaders().set("ETag", "\"" + snapshot.revision() + "\"");
                send(exchange, 200, snapshot);
            }
            case "PUT /api/file" -> {
                String revision = exchange.getRequestHeaders().getFirst("If-Match");
                if (revision == null || !revision.matches("\"(?:new|[0-9a-f]{64})\"")) {
                    send(exchange, 428, Map.of("error", "If-Match revision required")); return;
                }
                byte[] bytes = exchange.getRequestBody().readNBytes(ModuleSourceStore.maxBytes + 1);
                if (bytes.length > ModuleSourceStore.maxBytes) { send(exchange, 413, Map.of("error", "Source exceeds 256 KiB")); return; }
                send(exchange, 200, store.save(id, new String(bytes, StandardCharsets.UTF_8), revision.substring(1, revision.length() - 1)));
            }
            case "POST /api/operation" -> {
                ModuleSourceStore.validateId(id);
                String action = query.get("action");
                if (action == null || !Set.of("build", "load", "reload", "unload").contains(action)) throw new IllegalArgumentException("Unknown operation");
                send(exchange, 202, Map.of("job", jobs.track(operations.apply(id, action))));
            }
            case "GET /api/job" -> {
                JobRegistry.Job job = jobs.get(id);
                if (job == null) send(exchange, 404, Map.of("error", "Job not found")); else send(exchange, 200, job);
            }
            default -> send(exchange, 405, Map.of("error", "Unsupported endpoint or method"));
        }
    }
    private void staticFile(HttpExchange exchange, String path, String nonce) throws IOException {
        if (!exchange.getRequestMethod().equals("GET")) { send(exchange, 405, Map.of("error", "GET required")); return; }
        Map<String, String> assets = Map.of("/", "index.html", "/app.js", "app.js", "/style.css", "style.css", "/THIRD_PARTY_LICENSES.txt", "THIRD_PARTY_LICENSES.txt");
        String asset = assets.get(path);
        if (asset == null) { send(exchange, 404, Map.of("error", "Not found")); return; }
        try (InputStream input = getClass().getResourceAsStream("/webide/" + asset)) {
            if (input == null) { send(exchange, 404, Map.of("error", "Missing asset")); return; }
            String type = asset.endsWith("js") ? "text/javascript" : asset.endsWith("css") ? "text/css" : asset.endsWith("txt") ? "text/plain" : "text/html";
            byte[] bytes = input.readAllBytes();
            if (asset.equals("index.html")) bytes = new String(bytes, StandardCharsets.UTF_8)
                .replace("__CE_STYLE_NONCE__", nonce).getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("Content-Type", type + "; charset=utf-8");
            exchange.sendResponseHeaders(200, bytes.length); exchange.getResponseBody().write(bytes);
        }
    }
    private void send(HttpExchange exchange, int status, Object value) throws IOException {
        byte[] bytes = json.toJson(value).getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length); exchange.getResponseBody().write(bytes);
    }
    private static Map<String, String> query(String input) {
        Map<String, String> result = new HashMap<>();
        if (input == null) return result;
        for (String part : input.split("&")) {
            String[] pair = part.split("=", 2);
            String key = URLDecoder.decode(pair[0], StandardCharsets.UTF_8);
            String value = pair.length == 2 ? URLDecoder.decode(pair[1], StandardCharsets.UTF_8) : "";
            if (result.put(key, value) != null) throw new IllegalArgumentException("Duplicate query parameter");
        }
        return result;
    }
    @Override public void close() { server.stop(0); pool.shutdownNow(); }
}
