package com.securedoc.platform.infrastructure.delivery;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A made-up archive that behaves as {@code records-archive-v1} says: a delivery and a file are stored once, a repeat is
 * answered 200 with {@code replay}, the same reference with another body is 409. It counts what it stored, and answers
 * queued faults first.
 */
final class ArchiveServer implements AutoCloseable {

    record Call(String method, String path, Map<String, String> headers, String body) { }

    /** A queued answer. {@code afterWork}: the work is done first, and then this answer is given (a lost answer). */
    record Fault(int status, String code, String retryAfter, boolean afterWork) { }

    private static final Pattern FILES = Pattern.compile("/v1/deliveries/([^/]+)/files");

    final List<Call> calls = new ArrayList<>();
    final Map<String, String> deliveries = new ConcurrentHashMap<>();
    final Map<String, String> bodies = new ConcurrentHashMap<>();
    final Map<String, String> files = new ConcurrentHashMap<>();
    final Map<String, String> levels = new ConcurrentHashMap<>();
    final Deque<Fault> faults = new ArrayDeque<>();
    volatile String refuseToken;
    volatile int health = 200;
    volatile String healthCode;
    volatile String wrongChecksum;
    int tokens;
    private final HttpServer server;

    ArchiveServer() {
        try {
            server = HttpServer.create(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0), 0);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        server.createContext("/", this::handle);
        server.start();
    }

    String address() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    String tokenAddress() {
        return address() + "/token";
    }

    List<Call> of(String path) {
        return calls.stream().filter(c -> c.path().matches(path)).toList();
    }

    private synchronized void handle(HttpExchange x) throws IOException {
        byte[] body = x.getRequestBody().readAllBytes();
        String path = x.getRequestURI().getPath();
        Map<String, String> headers = new LinkedHashMap<>();
        x.getRequestHeaders().forEach((k, v) -> headers.put(k.toLowerCase(java.util.Locale.ROOT), v.get(0)));
        calls.add(new Call(x.getRequestMethod(), path, headers, new String(body, StandardCharsets.UTF_8)));
        if (path.equals("/token")) {
            tokens++;
            answer(x, 200, "{\"access_token\":\"token-" + tokens + "\",\"token_type\":\"Bearer\",\"expires_in\":300}", null);
            return;
        }
        if (path.equals("/ready")) {
            answer(x, health, healthCode == null ? "{}" : "{\"code\":\"" + healthCode + "\"}", healthCode == null ? null : "7");
            return;
        }
        String bearer = headers.getOrDefault("authorization", "");
        if (!bearer.startsWith("Bearer token-") || bearer.equals(refuseToken)) {
            answer(x, 401, "{\"code\":\"ARC-TOKEN\"}", null);
            return;
        }
        Fault fault = faults.poll();
        if (fault != null && !fault.afterWork()) {
            answer(x, fault.status(), "{\"code\":\"" + fault.code() + "\"}", fault.retryAfter());
            return;
        }
        int status;
        String json;
        Matcher m = FILES.matcher(path);
        if (path.equals("/v1/deliveries")) {
            String text = new String(body, StandardCharsets.UTF_8);
            Matcher ref = Pattern.compile("\"ref\":\"([^\"]+)\"").matcher(text);
            if (!ref.find()) throw new IllegalStateException("no ref");
            String known = bodies.get(ref.group(1));
            if (known != null && !known.equals(text)) {
                answer(x, 409, "{\"code\":\"ARC-CONFLICT\"}", null);
                return;
            }
            status = known == null ? 201 : 200;
            bodies.put(ref.group(1), text);
            String id = deliveries.computeIfAbsent(ref.group(1), k -> "dlv-" + (deliveries.size() + 1));
            json = "{\"deliveryId\":\"" + id + "\",\"state\":\"OPEN\",\"replay\":" + (known != null) + "}";
        } else if (m.matches()) {
            String item = headers.get("x-archive-item-ref");
            String sha = sha(body);
            boolean replay = files.containsKey(item);
            files.putIfAbsent(item, sha);
            levels.putIfAbsent(item, headers.getOrDefault("x-archive-level", "-"));
            status = replay ? 200 : 201;
            json = "{\"fileId\":\"file-" + item + "\",\"sha256\":\"" + (wrongChecksum == null ? sha : wrongChecksum)
                    + "\",\"size\":" + body.length + ",\"replay\":" + replay + "}";
        } else {
            status = 404;
            json = "{}";
        }
        if (fault != null) answer(x, fault.status(), "{\"code\":\"" + fault.code() + "\"}", fault.retryAfter());
        else answer(x, status, json, null);
    }

    private static void answer(HttpExchange x, int status, String json, String retryAfter) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        x.getResponseHeaders().add("Content-Type", "application/json");
        if (retryAfter != null) x.getResponseHeaders().add("Retry-After", retryAfter);
        x.sendResponseHeaders(status, b.length);
        x.getResponseBody().write(b);
        x.close();
    }

    static String sha(byte[] b) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(b));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    @Override
    public void close() {
        server.stop(0);
    }
}
