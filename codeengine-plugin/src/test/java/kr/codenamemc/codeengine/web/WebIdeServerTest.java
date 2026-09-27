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
