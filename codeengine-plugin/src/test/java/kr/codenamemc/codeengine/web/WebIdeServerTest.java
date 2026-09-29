package kr.codenamemc.codeengine.web;
import java.net.*;
import java.net.http.*;
import java.nio.file.Path;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;
import kr.codenamemc.codeengine.workspace.ModuleSourceStore;
import static org.junit.jupiter.api.Assertions.*;

class WebIdeServerTest {
    @TempDir Path directory;
    WebIdeServer server;
    String address, token;
    HttpClient client;
    @BeforeEach void start() throws Exception {
        var store = new ModuleSourceStore(directory); store.save("hello", "module hello;", "new");
        server = new WebIdeServer(store, (id, action) -> CompletableFuture.completedFuture(action + " " + id), Set::of, 0);
        String[] parts = server.url().split("#"); address = parts[0]; token = parts[1]; client = HttpClient.newHttpClient();
    }
    @AfterEach void stop() { server.close(); client.close(); }
    HttpResponse<String> request(String path, String method, String body, String... headers) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(address + path));
        if (headers.length > 0) builder.headers(headers);
        return client.send(builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
    @Test void apiRequiresBearer() throws Exception { assertEquals(401, request("api/modules", "GET", "").statusCode()); }
    @Test void listAndStaticUiWork() throws Exception {
        var response = request("api/modules", "GET", "", "Authorization", "Bearer " + token);
        assertEquals(200, response.statusCode()); assertTrue(response.body().contains("hello"));
        var page = request("", "GET", ""); assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElse("").contains("frame-ancestors 'none'"));
        assertFalse(page.body().contains(token));
    }
    @Test void studioAssetsUseNonceAndOnlyPackagedFilesAreServed() throws Exception {
        var page = request("", "GET", "");
        var next = request("", "GET", "");
        var matcher = java.util.regex.Pattern.compile("name=\"ce-style-nonce\" content=\"([^\"]+)\"").matcher(page.body());
        assertTrue(matcher.find());
        String nonce = matcher.group(1);
        assertFalse(nonce.equals("__CE_STYLE_NONCE__"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("'nonce-" + nonce + "'"));
        assertFalse(next.body().contains(nonce));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("script-src 'self';"));
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("style-src-attr 'unsafe-inline'"));
        var js = request("app.js", "GET", ""); assertEquals(200, js.statusCode());
        assertTrue(js.headers().firstValue("Content-Type").orElseThrow().startsWith("text/javascript"));
        assertEquals(200, request("style.css", "GET", "").statusCode());
        assertEquals(200, request("intelligence-worker.js", "GET", "").statusCode());
        assertTrue(page.headers().firstValue("Content-Security-Policy").orElseThrow().contains("worker-src 'self'"));
        assertEquals(200, request("THIRD_PARTY_LICENSES.txt", "GET", "").statusCode());
        assertEquals(404, request("src/App.tsx", "GET", "").statusCode());
        assertEquals(404, request("package.json", "GET", "").statusCode());
    }
    @Test void artifactEndpointsAreAuthenticatedAndStreamOnlyRegisteredFiles() throws Exception {
        server.close();
        var source = java.nio.file.Files.write(directory.resolve("fixture.jar"), new byte[] { 80, 75, 3, 4, 10 });
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var artifacts = new kr.codenamemc.codeengine.intelligence.IntelligenceArtifacts(directory.resolve("api-cache"), "fixture", root -> {
            entered.countDown(); release.await();
            return new kr.codenamemc.codeengine.intelligence.IntelligenceArtifacts.Prepared("fixture", "exact", "fixture", java.util.List.of(source));
        });
        server = new WebIdeServer(new ModuleSourceStore(directory.resolve("modules")),
            (id, action) -> CompletableFuture.completedFuture("ok"), Set::of, 0, artifacts);
        String[] parts = server.url().split("#"); address = parts[0]; token = parts[1];
        try {
            assertEquals(401, request("api/intelligence", "GET", "").statusCode());
            assertEquals(401, request("api/intelligence/artifact?id=anything", "GET", "").statusCode());
            assertEquals(1, entered.getCount(), "Unauthenticated traffic must not trigger downloads");
            String auth = "Bearer " + token;
            var loading = request("api/intelligence", "GET", "", "Authorization", auth);
            assertTrue(loading.body().contains("loading"));
            assertTrue(entered.await(5, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(404, request("api/intelligence/artifact?id=anything", "GET", "", "Authorization", auth).statusCode());
            release.countDown();
            com.google.gson.JsonObject state = null;
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(10);
            do {
                state = new com.google.gson.Gson().fromJson(request("api/intelligence", "GET", "", "Authorization", auth).body(), com.google.gson.JsonObject.class);
                if (!state.get("status").getAsString().equals("loading")) break;
                Thread.sleep(10);
            } while (System.nanoTime() < deadline);
            assertEquals("ready", state.get("status").getAsString(), state.toString());
            var artifact = state.getAsJsonArray("artifacts").get(0).getAsJsonObject();
            var result = client.send(HttpRequest.newBuilder(URI.create(address.substring(0, address.length() - 1) + artifact.get("url").getAsString()))
                .header("Authorization", auth).build(), HttpResponse.BodyHandlers.ofByteArray());
            assertEquals(200, result.statusCode());
            assertArrayEquals(java.nio.file.Files.readAllBytes(source), result.body());
            assertEquals("application/java-archive", result.headers().firstValue("Content-Type").orElseThrow());
            assertEquals("\"" + artifact.get("sha256").getAsString() + "\"", result.headers().firstValue("ETag").orElseThrow());
            assertEquals(404, request("api/intelligence/artifact?id=..%2Fconfig.yml", "GET", "", "Authorization", auth).statusCode());
            assertEquals(401, request("api/intelligence", "GET", "", "Authorization", auth, "Origin", "https://evil.example").statusCode());
        } finally { release.countDown(); }
    }
    @Test void crossOriginRequestRejected() throws Exception {
        assertEquals(401, request("api/modules", "GET", "", "Authorization", "Bearer " + token, "Origin", "https://evil.example").statusCode());
    }
    @Test void saveRequiresRevisionAndProtectsConflicts() throws Exception {
        assertEquals(428, request("api/file?id=hello", "PUT", "bad", "Authorization", "Bearer " + token).statusCode());
        assertEquals(409, request("api/file?id=hello", "PUT", "bad", "Authorization", "Bearer " + token, "If-Match", "\"new\"").statusCode());
        assertEquals(200, request("api/file?id=newmodule", "PUT", "module newmodule;", "Authorization", "Bearer " + token, "If-Match", "\"new\"").statusCode());
    }
    @Test void traversalAndDuplicateArgumentsAreRejected() throws Exception {
        assertEquals(400, request("api/file?id=..%2Fconfig", "GET", "", "Authorization", "Bearer " + token).statusCode());
        assertEquals(400, request("api/file?id=hello&id=other", "GET", "", "Authorization", "Bearer " + token).statusCode());
        assertEquals(404, request("config.yml", "GET", "").statusCode());
    }
    @Test void renameAndDeleteRequireRevisionAndAuthentication() throws Exception {
        String auth = "Bearer " + token;
        String revision = new com.google.gson.Gson().fromJson(
            request("api/file?id=hello", "GET", "", "Authorization", auth).body(),
            com.google.gson.JsonObject.class).get("revision").getAsString();
        assertEquals(401, request("api/file?id=hello", "DELETE", "").statusCode());
        assertEquals(428, request("api/file?id=hello", "DELETE", "", "Authorization", auth).statusCode());
        assertEquals(409, request("api/file/rename?id=hello&to=other", "POST", "", "Authorization", auth, "If-Match", "\"new\"").statusCode());
        assertEquals(200, request("api/file/rename?id=hello&to=other", "POST", "", "Authorization", auth, "If-Match", "\"" + revision + "\"").statusCode());
        assertEquals(404, request("api/file?id=hello", "GET", "", "Authorization", auth).statusCode());
        var renamed = request("api/file?id=other", "GET", "", "Authorization", auth);
        assertTrue(renamed.body().contains("module other;"));
        String renamedRevision = new com.google.gson.Gson().fromJson(renamed.body(), com.google.gson.JsonObject.class).get("revision").getAsString();
        assertEquals(200, request("api/file?id=other", "DELETE", "", "Authorization", auth, "If-Match", "\"" + renamedRevision + "\"").statusCode());
        assertEquals(404, request("api/file?id=other", "GET", "", "Authorization", auth).statusCode());
    }
    @Test void operationsReturnPollableJobs() throws Exception {
        var result = request("api/operation?id=hello&action=build", "POST", "", "Authorization", "Bearer " + token);
        assertEquals(202, result.statusCode());
        String job = new com.google.gson.Gson().fromJson(result.body(), com.google.gson.JsonObject.class).get("job").getAsString();
        var status = request("api/job?id=" + job, "GET", "", "Authorization", "Bearer " + token);
        assertTrue(status.body().contains("success"));
    }
    @Test void oversizedBodyRejected() throws Exception {
        assertEquals(413, request("api/file?id=large", "PUT", "x".repeat(262145), "Authorization", "Bearer " + token, "If-Match", "\"new\"").statusCode());
    }
}
