package com.securedoc.platform.ai.analysis;

import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashMap;
import java.util.Map;
import java.util.OptionalInt;
import java.util.PriorityQueue;
import java.util.Random;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0067: the rule that learns an AI server's requests at once, against a simulated AI server in simulated time (a
 * wall clock on a busy machine would measure the machine). The server works on some requests at once, queues the rest
 * first in, first out, and may refuse above a number.
 */
class LearnedServerLimitTest {
    private static final long MS = 1_000_000L;
    private final Map<String, Integer> remembered = new HashMap<>();
    private final AiServerRequestLimit.Store store = new AiServerRequestLimit.Store() {
        @Override
        public void saved(String server, int limit) {
            remembered.put(server, limit);
        }

        @Override
        public OptionalInt start(String server) {
            return OptionalInt.empty();
        }
    };
    private final Random random = new Random(67);
    private long now;
    private int workers = 100;
    private int refuseAbove = 100;
    private int refusal = 429;
    private int most;

    @Test
    void itSettlesAtTheNumberTheServerTakesAndDropsWhenTheServerTakesLess() {
        refuseAbove = 5;
        LearnedServerLimit limit = limit(16, Duration.ofHours(1));
        run(limit, 2000);
        assertEquals(5, limit.state().limit());
        assertEquals(5, limit.state().ceiling());
        assertEquals(6, most, "one more was tried, never many");
        assertEquals(5, remembered.get("server"));
        refuseAbove = 2;
        run(limit, 300);
        assertEquals(2, limit.state().limit(), "the server takes less now");
        refuseAbove = 9;
        run(limit, 300);
        assertEquals(2, limit.state().limit(), "the ceiling holds until it is checked again");
    }

    @Test
    void itStopsWhereTheServerOnlyQueuesWhateverThatNumberIs() {
        for (int takes : new int[]{1, 2, 3, 4, 6, 8}) {
            workers = takes;
            LearnedServerLimit limit = limit(16, Duration.ofHours(12));
            run(limit, 3000);
            assertEquals(takes, limit.state().limit(), takes + " at once: " + limit.state());
        }
    }

    @Test
    void slowAnswersHereAndThereSetNoCeiling() {
        workers = 100;
        LearnedServerLimit limit = limit(8, Duration.ofHours(12));
        for (int i = 0; i < 40; i++) {
            run(limit, 60);
            slowOnce = true;
        }
        assertEquals(8, limit.state().limit());
        assertNull(limit.state().ceiling());
    }

    @Test
    void aCeilingIsTriedAgainAfterItsTime() {
        refuseAbove = 3;
        LearnedServerLimit limit = limit(16, Duration.ofHours(1));
        run(limit, 600);
        assertEquals(3, limit.state().limit());
        refuseAbove = 6;
        now += Duration.ofMinutes(61).toNanos();
        run(limit, 2000);
        assertEquals(6, limit.state().limit(), "the customer upgraded the AI server");
    }

    @Test
    void neverAboveItsMaximumAndNeverBelowOne() {
        LearnedServerLimit limit = limit(3, Duration.ofHours(1));
        run(limit, 500);
        assertEquals(3, limit.state().limit());
        assertEquals(3, most);
        refuseAbove = 0;
        refusal = 503;
        run(limit, 100);
        assertEquals(1, limit.state().limit());
    }

    @Test
    void itGoesUpOnlyAfterItsTimeAndOnlyWhileRequestsWait() {
        LearnedServerLimit limit = new LearnedServerLimit("server", new AiServerRequestLimit.Learning(16,
                Duration.ofMinutes(2), Duration.ofHours(1)), store, 1, () -> now);
        run(limit, 100); // about 10 simulated seconds
        assertEquals(1, limit.state().limit());
        now += Duration.ofMinutes(3).toNanos();
        run(limit, 60);
        assertEquals(2, limit.state().limit());

        LearnedServerLimit quiet = limit(16, Duration.ofHours(1));
        for (int i = 0; i < 200; i++) {
            assertTrue(quiet.tryAcquire());
            now += 100 * MS;
            quiet.release(200, null, 100 * MS, "/api/chat");
        }
        assertEquals(1, quiet.state().limit(), "nobody waited");
    }

    @Test
    void afterARefusalWithRetryAfterNothingIsSentUntilThen() {
        LearnedServerLimit limit = limit(4, Duration.ofHours(1));
        assertTrue(limit.tryAcquire());
        limit.release(429, Duration.ofSeconds(30), MS, "/api/chat");
        assertTrue(!limit.tryAcquire());
        now += Duration.ofSeconds(29).toNanos();
        assertTrue(!limit.tryAcquire());
        now += Duration.ofSeconds(2).toNanos();
        assertTrue(limit.tryAcquire());
    }

    private boolean slowOnce;

    private LearnedServerLimit limit(int max, Duration recheck) {
        return new LearnedServerLimit("server", new AiServerRequestLimit.Learning(max, Duration.ofMillis(200), recheck),
                store, 1, () -> now);
    }

    private record Answer(long at, long sentAt, int status) { }

    /** More requests than places wait all the time, until {@code answers} answers came. */
    private void run(LearnedServerLimit limit, int answers) {
        PriorityQueue<Answer> open = new PriorityQueue<>(Comparator.comparingLong(Answer::at));
        long[] free = new long[Math.max(1, workers)];
        int done = 0;
        while (done < answers || !open.isEmpty()) {
            while (done < answers && limit.tryAcquire()) {
                most = Math.max(most, open.size() + 1);
                if (open.size() + 1 > refuseAbove) {
                    open.add(new Answer(now + MS, now, refusal));
                    continue;
                }
                long work = (long) (100 * MS * (0.85 + 0.3 * random.nextDouble()));
                if (slowOnce) work *= 20;
                slowOnce = false;
                int worker = 0;
                for (int w = 1; w < free.length; w++) if (free[w] < free[worker]) worker = w;
                long start = Math.max(now, free[worker]);
                free[worker] = start + work;
                open.add(new Answer(start + work, now, 200));
            }
            if (open.isEmpty()) {
                now += MS;
                continue;
            }
            Answer next = open.poll();
            now = Math.max(now, next.at());
            limit.release(next.status(), null, next.at() - next.sentAt(), "/api/chat");
            done++;
        }
        Arrays.fill(free, 0);
    }
}
