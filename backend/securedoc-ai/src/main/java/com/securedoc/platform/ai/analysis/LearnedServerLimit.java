package com.securedoc.platform.ai.analysis;

import java.time.Duration;
import java.util.ArrayDeque;
import java.util.HashMap;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.LongSupplier;

/**
 * Task 0067: the requests at once of one AI server. While requests wait and answers stay healthy the number goes up by
 * one; when the server refuses (429 or 503) or only answers slower (it queues them), it goes back down by one and that
 * is its ceiling until it is tried again. Never below 1 or above the maximum. Nothing of a request or an answer is kept
 * but its time.
 */
final class LearnedServerLimit {
    /** Answers looked at before a number is judged: this many per request at once, at least {@link #LEAST_ANSWERS}. */
    private static final int ANSWERS_PER_PLACE = 4;
    private static final int LEAST_ANSWERS = 16;
    /** The latest answers kept per number and kind. */
    private static final int MOST_ANSWERS = 64;
    /** Share of the slowdown of pure queueing that counts as "only queueing": one more must bring at least half. */
    private static final double QUEUEING = 0.5;
    private static final Duration LONGEST_WAIT = Duration.ofMinutes(5);

    private final String name;
    /** Null: the number is fixed. */
    private final AiServerRequestLimit.Learning settings;
    private final AiServerRequestLimit.Store store;
    private int limit;
    private Integer ceiling;
    private long ceilingUntil;
    private final LongSupplier clock;
    private long changedAt;
    private long pausedUntil;
    private int inFlight;
    private int waiting;
    /** Whether someone waited for a place since the last change: only then is one more worth trying. */
    private boolean wanted;
    private boolean suspected;
    /** Answers still to pass over after a change: they were sent at the number before. */
    private int unsettled;
    /** Answer times by number at once and kind of request (its path), in nanoseconds. */
    private final TreeMap<Integer, Map<String, ArrayDeque<Long>>> answers = new TreeMap<>();

    LearnedServerLimit(String name, AiServerRequestLimit.Learning settings, AiServerRequestLimit.Store store,
                       int limit) {
        this(name, settings, store, limit, System::nanoTime);
    }

    /** With a clock, for tests in simulated time. */
    LearnedServerLimit(String name, AiServerRequestLimit.Learning settings, AiServerRequestLimit.Store store,
                       int limit, LongSupplier clock) {
        this.clock = clock;
        this.changedAt = clock.getAsLong();
        this.pausedUntil = changedAt;
        this.name = name;
        this.settings = settings;
        this.store = store;
        this.limit = limit;
    }

    synchronized AiServerRequestLimit.ServerState state() {
        return new AiServerRequestLimit.ServerState(name, limit, ceiling, settings == null ? limit : settings.max(),
                settings != null, inFlight, waiting);
    }

    synchronized void acquire() throws InterruptedException {
        waiting++;
        try {
            while (true) {
                if (tryAcquire()) break;
                long pause = pausedUntil - clock.getAsLong();
                if (pause > 0) wait(pause / 1_000_000L + 1);
                else wait();
            }
        } finally {
            waiting--;
            notifyAll();
        }
    }

    /** Takes a place if there is one now. */
    synchronized boolean tryAcquire() {
        if (pausedUntil - clock.getAsLong() > 0) return false;
        if (inFlight >= limit) {
            wanted = true;
            return false;
        }
        inFlight++;
        return true;
    }

    /** @param status the answer's HTTP status; null when there was no answer (not a sign of too many requests) */
    synchronized void release(Integer status, Duration retryAfter, long nanos, String kind) {
        int atOnce = inFlight;
        inFlight--;
        if (status != null && (status == 429 || status == 503)) {
            refused(retryAfter, atOnce);
        } else if (settings != null && status != null && status == 200) {
            // Only an answer given at the full number says something about it. With others waiting it was full too: of
            // several that end together, only the first release still sees every place taken.
            if ((atOnce == limit || waiting > 0) && --unsettled < 0) {
                ArrayDeque<Long> times = answers.computeIfAbsent(limit, n -> new HashMap<>())
                        .computeIfAbsent(kind == null ? "" : kind, k -> new ArrayDeque<>());
                times.addLast(nanos);
                if (times.size() > MOST_ANSWERS) times.removeFirst();
            }
            judge();
        }
        notifyAll();
    }

    private void refused(Duration retryAfter, int atOnce) {
        if (retryAfter != null) {
            Duration wait = retryAfter.compareTo(LONGEST_WAIT) > 0 ? LONGEST_WAIT : retryAfter;
            pausedUntil = clock.getAsLong() + wait.toNanos();
        }
        // A refusal of a request sent before the last step down says nothing about the number now.
        if (settings != null && limit > 1 && atOnce <= limit) down();
    }

    private void judge() {
        if (limit > 1 && slower()) {
            // Looked at twice before a ceiling is set: it holds for a long time.
            if (suspected) {
                down();
            } else {
                suspected = true;
                answers.remove(limit);
            }
            return;
        }
        long now = clock.getAsLong();
        boolean mayTry = ceiling == null || limit < ceiling || now - ceilingUntil >= 0;
        if (wanted && mayTry && limit < settings.max() && now - changedAt >= settings.upEvery().toNanos()
                && count(limit) >= needed(limit)) {
            if (ceiling != null && limit >= ceiling) ceiling = null;
            change(limit + 1);
        }
    }

    /** With enough answers at this number and the one below: it only queues what is sent. */
    private boolean slower() {
        Map<String, ArrayDeque<Long>> here = answers.get(limit);
        Map<String, ArrayDeque<Long>> below = answers.get(limit - 1);
        if (here == null || below == null || count(limit) < needed(limit)) return false;
        double ratio = 0;
        double weight = 0;
        for (var entry : here.entrySet()) {
            ArrayDeque<Long> before = below.get(entry.getKey());
            if (before == null || before.size() < LEAST_ANSWERS / 2) continue;
            int count = entry.getValue().size();
            ratio += count * typical(entry.getValue()) / typical(before);
            weight += count;
        }
        return weight >= LEAST_ANSWERS / 2.0 && ratio / weight > 1 + QUEUEING / (limit - 1);
    }

    /** The mean, with no answer counted as more than three typical ones (a long page, a model being loaded). */
    private static double typical(ArrayDeque<Long> times) {
        long[] sorted = times.stream().mapToLong(Long::longValue).sorted().toArray();
        long most = 3 * Math.max(1, sorted[sorted.length / 2]);
        return Math.max(1, java.util.Arrays.stream(sorted).map(t -> Math.min(t, most)).average().orElse(1));
    }

    private static int needed(int number) {
        return Math.max(LEAST_ANSWERS, ANSWERS_PER_PLACE * number);
    }

    private double count(int number) {
        return answers.getOrDefault(number, Map.of()).values().stream().mapToDouble(ArrayDeque::size).sum();
    }

    private void down() {
        ceiling = limit - 1;
        ceilingUntil = clock.getAsLong() + settings.recheck().toNanos();
        change(limit - 1);
        try {
            store.saved(name, limit);
        } catch (RuntimeException notRemembered) {
            // Learned again after a restart.
        }
    }

    private void change(int number) {
        // The new number is measured anew; the one below it stays as the comparison.
        answers.remove(number);
        answers.headMap(number - 1).clear();
        answers.tailMap(number + 1).clear();
        limit = number;
        changedAt = clock.getAsLong();
        wanted = false;
        suspected = false;
        unsettled = 2 * number;
    }
}
