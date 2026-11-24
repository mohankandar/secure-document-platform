package com.securedoc.platform.ai.analysis;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.net.URI;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0049, step 3: requests at once per AI server. */
class AiServerRequestLimitTest {
    private static final URI ONE = URI.create("http://ai-one:11434/api/chat");
    private static final URI OTHER = URI.create("http://ai-two:11434/api/chat");

    @AfterEach
    void off() {
        AiServerRequestLimit.set(0);
    }

    @Test
    void neverMoreThanTheLimitAtOnceAndEachServerHasItsOwn() throws Exception {
        AiServerRequestLimit.set(2);
        assertEquals(2, mostAtOnce(12, ONE));
        assertEquals(4, mostAtOnce(12, ONE, OTHER));
    }

    @Test
    void withoutALimitNothingWaits() throws Exception {
        AiServerRequestLimit.set(0);
        assertTrue(mostAtOnce(12, ONE) > 2);
    }

    @Test
    void aFailedRequestGivesItsPlaceBack() throws Exception {
        AiServerRequestLimit.set(1);
        for (int i = 0; i < 3; i++) {
            assertThrows(java.io.IOException.class, () -> AiServerRequestLimit.send(ONE, () -> {
                throw new java.io.IOException("test");
            }));
        }
        assertEquals("ok", AiServerRequestLimit.send(ONE, () -> "ok"));
    }

    private static int mostAtOnce(int requests, URI... servers) throws Exception {
        AtomicInteger now = new AtomicInteger();
        AtomicInteger most = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(requests);
        try {
            List<Future<?>> all = new ArrayList<>();
            for (int i = 0; i < requests; i++) {
                URI server = servers[i % servers.length];
                all.add(pool.submit(() -> AiServerRequestLimit.send(server, () -> {
                    most.accumulateAndGet(now.incrementAndGet(), Math::max);
                    Thread.sleep(60);
                    return now.decrementAndGet();
                })));
            }
            for (Future<?> f : all) f.get();
        } finally {
            pool.shutdownNow();
        }
        return most.get();
    }
}
