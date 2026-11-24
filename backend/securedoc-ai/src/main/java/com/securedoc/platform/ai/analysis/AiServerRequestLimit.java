package com.securedoc.platform.ai.analysis;

import java.io.IOException;
import java.net.URI;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Semaphore;

/**
 * Task 0049: how many requests this LexaVault server sends to one AI server at once, over all documents and page
 * batches. Off (today's flow) until a number is set. A request's own timeout starts when it is sent, not while it waits
 * for its turn. The learned limit is P3.
 */
public final class AiServerRequestLimit {
    private static volatile int limit;
    private static volatile Map<String, Semaphore> servers = new ConcurrentHashMap<>();

    private AiServerRequestLimit() {
    }

    /** Requests at once per AI server; 0 for no limit. Set once, when the application starts. */
    public static synchronized void set(int requestsAtOnce) {
        if (requestsAtOnce < 0) throw new IllegalArgumentException("requests at once must be >= 0");
        servers = new ConcurrentHashMap<>();
        limit = requestsAtOnce;
    }

    /** One request to an AI server. */
    @FunctionalInterface
    public interface Request<T> {
        T send() throws IOException, InterruptedException;
    }

    /** Sends the request when the AI server has a free place. */
    public static <T> T send(URI endpoint, Request<T> request) throws IOException, InterruptedException {
        int places = limit;
        if (places == 0) return request.send();
        Semaphore server = servers.computeIfAbsent(
                (endpoint.getScheme() + "://" + endpoint.getAuthority()).toLowerCase(Locale.ROOT),
                key -> new Semaphore(places, true));
        server.acquire();
        try {
            return request.send();
        } finally {
            server.release();
        }
    }
}
