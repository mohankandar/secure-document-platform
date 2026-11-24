package com.securedoc.platform.api.processing;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Task 0067: workers of one kind whose number can be changed at any time. A worker above the number finishes the job it
 * has and then waits without looking for work; nothing is interrupted but by {@link #stop}.
 */
final class ElasticWorkers {
    /** One turn of a worker. */
    interface Turn {
        /**
         * Looks for a job and runs it.
         *
         * @param started called when a job was claimed, before it runs
         * @return false when there was none
         */
        boolean run(String owner, Runnable started) throws InterruptedException;
    }

    private final String threadName;
    private final String owner;
    private final String instance;
    private final long idleMs;
    private final Turn turn;
    private final List<Thread> threads = new ArrayList<>();
    private final AtomicInteger busy = new AtomicInteger();
    private int number;
    private boolean stopped;

    ElasticWorkers(String threadName, String owner, String instance, long idleMs, Turn turn) {
        this.threadName = threadName;
        this.owner = owner;
        this.instance = instance;
        this.idleMs = idleMs;
        this.turn = turn;
    }

    synchronized void set(int workers) {
        if (stopped) return;
        number = workers;
        while (threads.size() < workers) {
            int index = threads.size();
            Thread thread = new Thread(() -> loop(index), threadName + "-" + (index + 1));
            thread.setDaemon(true);
            threads.add(thread);
            thread.start();
        }
        notifyAll();
    }

    synchronized int number() {
        return number;
    }

    int busy() {
        return busy.get();
    }

    synchronized void stop() {
        stopped = true;
        threads.forEach(Thread::interrupt);
        notifyAll();
    }

    private synchronized boolean mayLook(int index) throws InterruptedException {
        while (!stopped && index >= number) wait();
        return !stopped;
    }

    private void loop(int index) {
        String name = owner + "-worker-" + (index + 1) + "/" + instance;
        try {
            while (mayLook(index) && !Thread.currentThread().isInterrupted()) {
                int[] counted = {0};
                boolean ran;
                try {
                    ran = turn.run(name, () -> {
                        counted[0] = 1;
                        busy.incrementAndGet();
                    });
                } finally {
                    busy.addAndGet(-counted[0]);
                }
                if (!ran) Thread.sleep(idleMs);
            }
        } catch (InterruptedException stop) {
            Thread.currentThread().interrupt();
        }
    }
}
