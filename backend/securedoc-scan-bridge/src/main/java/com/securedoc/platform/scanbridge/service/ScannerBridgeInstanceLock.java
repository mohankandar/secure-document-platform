package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import jakarta.annotation.PostConstruct;
import jakarta.annotation.PreDestroy;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Prevents two Scanner Bridge processes from owning the same durable queue/work root.
 * Port checks are only launcher convenience; filesystem ownership is the durable safety boundary.
 */
@Component
public class ScannerBridgeInstanceLock {
    private static final String LOCK_FILE = ".scanner-bridge.lock";

    private final ScanBridgeProperties properties;
    private FileChannel channel;
    private FileLock lock;

    public ScannerBridgeInstanceLock(ScanBridgeProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public synchronized void acquire() {
        Path workRoot = Path.of(properties.getWorkPath()).toAbsolutePath().normalize();
        try {
            Files.createDirectories(workRoot);
            Path lockPath = workRoot.resolve(LOCK_FILE);
            channel = FileChannel.open(lockPath, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                lock = channel.tryLock();
            } catch (OverlappingFileLockException ex) {
                lock = null;
            }
            if (lock == null) {
                closeChannelQuietly();
                throw new IllegalStateException("Another Scanner Bridge process already owns the configured work path");
            }

            String owner = "bridgeId=" + safe(properties.getBridgeId()) + System.lineSeparator()
                    + "processId=" + ProcessHandle.current().pid() + System.lineSeparator();
            channel.truncate(0);
            channel.position(0);
            ByteBuffer bytes = StandardCharsets.UTF_8.encode(owner);
            while (bytes.hasRemaining()) channel.write(bytes);
            channel.force(true);
        } catch (IOException ex) {
            release();
            throw new IllegalStateException("Unable to acquire Scanner Bridge work-path ownership", ex);
        }
    }

    @PreDestroy
    public synchronized void release() {
        if (lock != null) {
            try { lock.release(); } catch (IOException ignored) { }
            lock = null;
        }
        closeChannelQuietly();
    }

    private void closeChannelQuietly() {
        if (channel != null) {
            try { channel.close(); } catch (IOException ignored) { }
            channel = null;
        }
    }

    private String safe(String value) {
        if (value == null) return "unknown";
        return value.replaceAll("[\\r\\n]", "").trim();
    }
}
