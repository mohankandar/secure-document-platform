package com.securedoc.platform.ai.analysis;

import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0067: the limit on real requests to a mock AI server; what it learns is proven in LearnedServerLimitTest. */
class AiServerLearnedLimitTest {
    private static final Duration LONG = Duration.ofHours(1);
    /** Long against the machine's own noise, as a real answer is. */
    private volatile int workMs = 120;
    private HttpServer mock;
    private ExecutorService mockThreads;
    private URI uri;
    /** Above this many at once the mock refuses. */
    private volatile int refuseAbove = 100;
    /** This many are worked on at once; the others wait their turn inside the mock (it only queues them). */
    private volatile Semaphore working = new Semaphore(100, true);
    private volatile String retryAfter;
    private volatile int refusal = 429;
    private final AtomicInteger now = new AtomicInteger();
    private final AtomicInteger most = new AtomicInteger();
    private final HttpClient http = HttpClient.newHttpClient();
    private final Map<String, Integer> remembered = new ConcurrentHashMap<>();
    private final AiServerRequestLimit.Store store = new AiServerRequestLimit.Store() {
        @Override
        public void saved(String server, int limit) {
            remembered.put(server, limit);
        }

        @Override
        public OptionalInt start(String server) {
            return remembered.containsKey(server) ? OptionalInt.of(remembered.get(server)) : OptionalInt.empty();
        }
    };

    @BeforeEach
    void start() throws Exception {
        mockThreads = Executors.newCachedThreadPool();
        mock = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mock.setExecutor(mockThreads);
        mock.createContext("/", exchange -> {
            int atOnce = now.incrementAndGet();
            most.accumulateAndGet(atOnce, Math::max);
            int status = 200;
            if (atOnce > refuseAbove) {
                status = refusal;
                if (retryAfter != null) exchange.getResponseHeaders().add("Retry-After", retryAfter);
            } else {
                Semaphore turn = working;
                turn.acquireUninterruptibly();
                try {
                    Thread.sleep(workMs);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                } finally {
                    turn.release();
                }
            }
            now.decrementAndGet();
            exchange.sendResponseHeaders(status, -1);
            exchange.close();
        });
        mock.start();
        uri = URI.create("http://127.0.0.1:" + mock.getAddress().getPort() + "/api/chat");
    }

    @AfterEach
    void stop() {
        mock.stop(0);
        mockThreads.shutdownNow();
        AiServerRequestLimit.set(0);
    }

    @Test
    void neverAboveItsMaximumAndNeverBelowOne() throws Exception {
        learn(3, Duration.ofMillis(5), LONG);
        flood(150, 20);
        assertEquals(3, state().limit(), state().toString());
        assertTrue(most.get() <= 3);
        refuseAbove = 0;
        refusal = 503;
        flood(30, 10);
        assertEquals(1, state().limit());
        refuseAbove = 100;
        flood(5, 2);
        assertEquals(1, state().limit());
    }

    @Test
    void withoutWaitingRequestsNothingIsTried() throws Exception {
        learn(16, Duration.ofMillis(1), LONG);
        flood(40, 1);
        assertEquals(1, state().limit());
    }

    @Test
    void retryAfterIsHonoured() throws Exception {
        refuseAbove = 0;
        retryAfter = "1";
        learn(4, LONG, LONG);
        assertEquals(429, request());
        refuseAbove = 100;
        long before = System.nanoTime();
        assertEquals(200, request());
        assertTrue(System.nanoTime() - before >= 900_000_000L, "the next request waits as long as it was asked to");
    }

    @Test
    void theServersNameHoldsNoUserNameOrPath() throws Exception {
        learn(4, LONG, LONG);
        AiServerRequestLimit.send(URI.create("http://someone:word@ai-host:11434/api/chat?key=1"), () -> "x");
        assertEquals("http://ai-host:11434", AiServerRequestLimit.states().get(0).server());
        assertFalse(AiServerRequestLimit.states().toString().contains("someone"));
    }

    private void learn(int max, Duration upEvery, Duration recheck) {
        AiServerRequestLimit.learn(new AiServerRequestLimit.Learning(max, upEvery, recheck), store);
    }

    private AiServerRequestLimit.ServerState state() {
        assertEquals(1, AiServerRequestLimit.states().size());
        return AiServerRequestLimit.states().get(0);
    }

    private int request() throws Exception {
        return AiServerRequestLimit.send(uri, () -> http.send(HttpRequest.newBuilder(uri).build(),
                HttpResponse.BodyHandlers.discarding())).statusCode();
    }

    private void flood(int requests, int threads) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < requests; i++) all.add(pool.submit(this::request));
            for (Future<?> f : all) f.get();
        } finally {
            pool.shutdownNow();
        }
    }
}
