package com.securedoc.platform.scanbridge.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.ScanSession;
import org.springframework.stereotype.Component;
import org.springframework.context.annotation.DependsOn;

import java.io.IOException;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * File-backed durable state for the workstation Scanner Bridge.
 *
 * <p>State files live beside the bridge-owned PDF so a bridge restart or workstation reboot
 * does not discard a READY/QUEUED package. Writes use a same-directory temp file and atomic
 * replacement where supported.</p>
 */
@Component
@DependsOn("scannerBridgeInstanceLock")
public class FileScanSessionStore {
    static final String STATE_FILE = "session.json";
    private final Path workRoot;
    private final ObjectMapper objectMapper;

    public FileScanSessionStore(ScanBridgeProperties properties, ObjectMapper objectMapper) {
        this.workRoot = Path.of(properties.getWorkPath()).toAbsolutePath().normalize();
        this.objectMapper = objectMapper;
    }

    public synchronized void save(ScanSession session) {
        try {
            Path sessionDirectory = sessionDirectory(session.sessionId());
            Files.createDirectories(sessionDirectory);
            byte[] json = objectMapper.writerWithDefaultPrettyPrinter().writeValueAsBytes(session);
            Path state = sessionDirectory.resolve(STATE_FILE);
            Path temp = sessionDirectory.resolve(STATE_FILE + ".tmp");
            try (FileChannel channel = FileChannel.open(temp,
                    StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)) {
                java.nio.ByteBuffer buffer = java.nio.ByteBuffer.wrap(json);
                while (buffer.hasRemaining()) channel.write(buffer);
                channel.force(true);
            }
            try {
                Files.move(temp, state, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException ex) {
                Files.deleteIfExists(temp);
                throw new IllegalStateException("Scanner Bridge requires atomic state replacement on its work filesystem", ex);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to durably persist Scanner Bridge state", ex);
        }
    }

    public synchronized List<ScanSession> loadAll() {
        try {
            Files.createDirectories(workRoot);
            List<Path> stateFiles;
            try (var dirs = Files.list(workRoot)) {
                stateFiles = dirs.filter(Files::isDirectory)
                        .map(path -> path.resolve(STATE_FILE))
                        .filter(Files::isRegularFile)
                        .sorted(Comparator.comparing(Path::toString))
                        .toList();
            }
            List<ScanSession> sessions = new ArrayList<>();
            for (Path stateFile : stateFiles) {
                try {
                    ScanSession session = objectMapper.readValue(stateFile.toFile(), ScanSession.class);
                    validateStateLocation(stateFile, session);
                    sessions.add(session);
                } catch (Exception ex) {
                    throw new IllegalStateException("Scanner Bridge durable state is unreadable: " + stateFile, ex);
                }
            }
            return List.copyOf(sessions);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to read Scanner Bridge durable state", ex);
        }
    }

    /** Removes one terminal session directory after its retention window has elapsed. */
    public synchronized void deleteSession(UUID sessionId) {
        Path directory = sessionDirectory(sessionId);
        if (!Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.deleteIfExists(path);
            }
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to remove expired Scanner Bridge state", ex);
        }
    }

    public Path sessionDirectory(UUID sessionId) {
        Path resolved = workRoot.resolve(sessionId.toString()).normalize();
        if (!resolved.startsWith(workRoot)) {
            throw new IllegalArgumentException("Invalid scanner session path");
        }
        return resolved;
    }

    public Path workRoot() {
        return workRoot;
    }

    private void validateStateLocation(Path stateFile, ScanSession session) {
        if (session == null || session.sessionId() == null) {
            throw new IllegalArgumentException("Scanner state is missing session identity");
        }
        Path expected = sessionDirectory(session.sessionId()).resolve(STATE_FILE).normalize();
        if (!expected.equals(stateFile.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Scanner state file does not match its session identity");
        }
    }
}
