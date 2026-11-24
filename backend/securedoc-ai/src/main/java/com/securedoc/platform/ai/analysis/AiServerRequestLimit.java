package com.securedoc.platform.ai.analysis;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Task 0049: how many requests this LexaVault server sends to one AI server at once, over all documents and page
 * batches. A request's own timeout starts when it is sent, not while it waits for its turn. Task 0067: the number is
 * learned per AI server ({@link LearnedServerLimit}). No limit until {@link #set} or {@link #learn} is called.
 */
public final class AiServerRequestLimit {

    /** Where the number learned for an AI server (its scheme, host and port) is remembered. */
    public interface Store {
        void saved(String server, int limit);

        /** Where the learning starts again after a restart. */
        OptionalInt start(String server);
    }

    /**
     * @param max     IT's own maximum, or the most that is ever tried
     * @param upEvery the least time at one number before the next one is tried
     * @param recheck how long a ceiling holds before it is tried again
     */
    public record Learning(int max, Duration upEvery, Duration recheck) {
        public Learning {
            if (max < 1 || max > MOST) throw new IllegalArgumentException("the AI maximum must be between 1 and 64");
            Objects.requireNonNull(upEvery);
            Objects.requireNonNull(recheck);
        }
    }

    /** @param ceiling the most the server was found to take; null while none is known */
    public record ServerState(String server, int limit, Integer ceiling, int max, boolean learned, int inFlight,
                              int waiting) { }

    /** One request to an AI server. */
    @FunctionalInterface
    public interface Request<T> {
        T send() throws IOException, InterruptedException;
    }

    public static final int MOST = 64;
    /** The number below is the comparison, so the learning starts at the bottom. */
    private static final int START = 1;

    private static volatile int fixed;
    private static volatile Learning learning;
    private static volatile Store store;
    private static volatile Map<String, LearnedServerLimit> servers = new ConcurrentHashMap<>();

    private AiServerRequestLimit() {
    }

    /** A fixed number of requests at once per AI server; 0 for no limit. Set once, when the application starts. */
    public static synchronized void set(int requestsAtOnce) {
        if (requestsAtOnce < 0) throw new IllegalArgumentException("requests at once must be >= 0");
        servers = new ConcurrentHashMap<>();
        learning = null;
        fixed = requestsAtOnce;
    }

    /** The number is learned per AI server. Set once, when the application starts. */
    public static synchronized void learn(Learning settings, Store where) {
        servers = new ConcurrentHashMap<>();
        store = Objects.requireNonNull(where);
        learning = Objects.requireNonNull(settings);
    }

    /** Sends the request when the AI server has a free place. */
    public static <T> T send(URI endpoint, Request<T> request) throws IOException, InterruptedException {
        if (learning == null && fixed == 0) return request.send();
        LearnedServerLimit server = server(endpoint);
        server.acquire();
        long started = System.nanoTime();
        Integer status = null;
        Duration retryAfter = null;
        try {
            T answer = request.send();
            status = 200;
            if (answer instanceof HttpResponse<?> response) {
                status = response.statusCode();
                retryAfter = retryAfter(response);
            }
            return answer;
        } finally {
            server.release(status, retryAfter, System.nanoTime() - started, endpoint.getPath());
        }
    }

    /** Every AI server known so far. */
    public static List<ServerState> states() {
        return servers.values().stream().map(LearnedServerLimit::state)
                .sorted(Comparator.comparing(ServerState::server)).toList();
    }

    /** The requests this AI server takes at once now; {@link #MOST} without a limit. */
    public static int limitOf(URI endpoint) {
        return learning == null && fixed == 0 ? MOST : server(endpoint).state().limit();
    }

    private static LearnedServerLimit server(URI endpoint) {
        Learning settings = learning;
        // Scheme, host and port only: an address can hold a user name.
        String name = (endpoint.getScheme() + "://" + endpoint.getHost()
                + (endpoint.getPort() < 0 ? "" : ":" + endpoint.getPort())).toLowerCase(Locale.ROOT);
        return servers.computeIfAbsent(name, key -> settings == null ? new LearnedServerLimit(key, null, null, fixed)
                : new LearnedServerLimit(key, settings, store,
                        Math.max(1, Math.min(settings.max(), store.start(key).orElse(START)))));
    }

    private static Duration retryAfter(HttpResponse<?> response) {
        return response.headers().firstValue("Retry-After").map(String::trim).filter(v -> v.matches("\\d{1,6}"))
                .map(v -> Duration.ofSeconds(Long.parseLong(v))).orElse(null);
    }
}
