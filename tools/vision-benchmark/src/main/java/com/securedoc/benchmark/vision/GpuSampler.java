package com.securedoc.benchmark.vision;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;

/** Polls nvidia-smi for used GPU memory (MiB) every half second and keeps the highest value. */
public final class GpuSampler implements AutoCloseable {

    private final Thread thread;
    private volatile boolean running = true;
    private volatile int peakMiB = -1;

    public GpuSampler() {
        thread = new Thread(() -> {
            while (running) {
                int used = usedMiB();
                if (used > peakMiB) peakMiB = used;
                try {
                    Thread.sleep(500);
                } catch (InterruptedException e) {
                    return;
                }
            }
        }, "gpu-sampler");
        thread.setDaemon(true);
        thread.start();
    }

    public int peakMiB() {
        return peakMiB;
    }

    public static int usedMiB() {
        try {
            Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=memory.used", "--format=csv,noheader,nounits")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                p.waitFor();
                return line == null ? -1 : Integer.parseInt(line.trim());
            }
        } catch (Exception e) {
            return -1;
        }
    }

    public static String gpuName() {
        try {
            Process p = new ProcessBuilder("nvidia-smi", "--query-gpu=name,memory.total", "--format=csv,noheader")
                    .redirectErrorStream(true).start();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8))) {
                String line = r.readLine();
                p.waitFor();
                return line == null ? "unknown" : line.trim();
            }
        } catch (Exception e) {
            return "none";
        }
    }

    @Override
    public void close() {
        running = false;
        thread.interrupt();
    }
}
