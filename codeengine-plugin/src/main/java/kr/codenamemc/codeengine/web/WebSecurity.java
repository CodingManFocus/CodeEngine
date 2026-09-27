package kr.codenamemc.codeengine.web;

import com.sun.net.httpserver.HttpExchange;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.Set;

final class WebSecurity {
    private final String token;
    WebSecurity() { byte[] random = new byte[32]; new SecureRandom().nextBytes(random); token = Base64.getUrlEncoder().withoutPadding().encodeToString(random); }
    String token() { return token; }
    boolean validHost(HttpExchange exchange) {
        int port = exchange.getLocalAddress().getPort();
        String host = exchange.getRequestHeaders().getFirst("Host");
        return host != null && Set.of("127.0.0.1:" + port, "localhost:" + port).contains(host);
    }
    boolean authorize(HttpExchange exchange) {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        int port = exchange.getLocalAddress().getPort();
        if (origin != null && !Set.of("http://127.0.0.1:" + port, "http://localhost:" + port).contains(origin)) return false;
        String provided = exchange.getRequestHeaders().getFirst("Authorization");
        return provided != null && MessageDigest.isEqual(("Bearer " + token).getBytes(StandardCharsets.UTF_8), provided.getBytes(StandardCharsets.UTF_8));
    }
}
