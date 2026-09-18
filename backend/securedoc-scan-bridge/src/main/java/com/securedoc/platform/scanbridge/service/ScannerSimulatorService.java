package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.PreparedScan;
import com.securedoc.platform.scanbridge.model.ScanSession;
import com.securedoc.platform.scanbridge.model.ScannerCaptureMode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** QA/development acquisition adapter. It never becomes an automatic fallback for physical mode. */
@Service
public class ScannerSimulatorService implements ScannerAcquisitionAdapter {
    private static final Logger log = LoggerFactory.getLogger(ScannerSimulatorService.class);
    private static final byte[] PDF_MAGIC = new byte[] {'%', 'P', 'D', 'F', '-'};

    private final ScanBridgeProperties properties;
    private final ScanSessionService sessions;
    private final ScannerStorageHealthService storageHealth;
    private final Clock clock;
    private final StableFileTracker tracker = new StableFileTracker();

    @Autowired
    public ScannerSimulatorService(ScanBridgeProperties properties, ScanSessionService sessions,
                                   ScannerStorageHealthService storageHealth) {
        this(properties, sessions, storageHealth, Clock.systemUTC());
    }

    ScannerSimulatorService(ScanBridgeProperties properties, ScanSessionService sessions,
                            ScannerStorageHealthService storageHealth, Clock clock) {
        this.properties = properties;
        this.sessions = sessions;
        this.storageHealth = storageHealth;
        this.clock = clock;
    }

    @Override
    public ScannerCaptureMode mode() { return ScannerCaptureMode.SIMULATOR; }

    @Override
    public boolean available() { return properties.isEnabled() && properties.getMode() == ScannerCaptureMode.SIMULATOR; }

    @PostConstruct
    public void initializeDirectories() throws IOException {
        ensureDirectories();
        if (properties.getMode() == ScannerCaptureMode.SIMULATOR) {
            quarantineIncompleteSimulatorUploads();
            recoverClaimedPdfForWaitingSession();
            log.info("SCAN_BRIDGE_SIMULATOR_READY bridgeId={} siteId={} scannerId={}",
                    properties.getBridgeId(), properties.getSiteId(), properties.getScannerId());
        }
    }

    private void ensureDirectories() throws IOException {
        Files.createDirectories(workPath());
        Files.createDirectories(problemPath());
        if (properties.getMode() == ScannerCaptureMode.SIMULATOR) {
            Files.createDirectories(inboxPath());
            ensureSimulatorPathsShareFileStore();
        }
    }

    /**
     * Simulator acquisition depends on atomic rename semantics between inbox, work and problem
     * directories. Cross-volume paths would silently turn a crash-safe claim into a copy/delete
     * operation, so fail closed instead of degrading durability.
     */
    private void ensureSimulatorPathsShareFileStore() throws IOException {
        var inboxStore = Files.getFileStore(inboxPath());
        var workStore = Files.getFileStore(workPath());
        var problemStore = Files.getFileStore(problemPath());
        if (!inboxStore.equals(workStore) || !inboxStore.equals(problemStore)) {
            throw new IOException("Scanner simulator inbox, work, and problem paths must use the same filesystem for atomic moves");
        }
    }

    @Scheduled(fixedDelayString = "${securedoc.scan-bridge.simulator.scan-interval-ms:1000}")
    public void scheduledScan() {
        if (!available()) return;
        try {
            scanOnce();
        } catch (Exception ex) {
            log.warn("SCAN_BRIDGE_SIMULATOR_SCAN_FAILED reasonCode=SCAN_FAILED exceptionType={}", ex.getClass().getSimpleName());
        }
    }

    public void scanOnce() throws IOException {
        if (properties.getMode() != ScannerCaptureMode.SIMULATOR) return;
        ensureDirectories();
        List<Path> candidates;
        try (var stream = Files.list(inboxPath())) {
            candidates = stream.filter(Files::isRegularFile)
                    .filter(path -> !path.getFileName().toString().endsWith(".part"))
                    .sorted(Comparator.comparingLong(this::lastModifiedSafe)
                            .thenComparing(path -> path.getFileName().toString()))
                    .toList();
        }

        Duration stableWindow = Duration.ofMillis(Math.max(0, properties.getSimulator().getStableWindowMs()));
        Instant now = clock.instant();
        for (Path candidate : candidates) {
            BasicFileAttributes attributes = Files.readAttributes(candidate, BasicFileAttributes.class);
            if (!tracker.isStable(candidate, attributes.size(), attributes.lastModifiedTime().toMillis(), now, stableWindow)) continue;
            if (attributes.size() > storageHealth.maxScanPdfSizeBytes()) {
                moveToProblem(candidate, "SCAN_PDF_TOO_LARGE");
                continue;
            }
            if (!candidate.getFileName().toString().toLowerCase().endsWith(".pdf")) {
                moveToProblem(candidate, "UNSUPPORTED_FILE_TYPE");
                continue;
            }
            if (!hasPdfMagic(candidate)) {
                moveToProblem(candidate, "INVALID_PDF");
                continue;
            }
            var waiting = sessions.activeWaitingSession();
            if (waiting.isEmpty()) continue;
            claimForSession(candidate, attributes, waiting.get());
            break;
        }
    }

    @Override
    public void assertReadyForNewSession() {
        if (properties.getMode() != ScannerCaptureMode.SIMULATOR) {
            throw new ScanBridgeException("SIMULATOR_NOT_ENABLED", "Scanner simulator mode is not enabled.", HttpStatus.CONFLICT);
        }
        try {
            ensureDirectories();
            try (var stream = Files.list(inboxPath())) {
                if (stream.filter(Files::isRegularFile)
                        .anyMatch(path -> !path.getFileName().toString().endsWith(".part"))) {
                    throw new ScanBridgeException("SCANNER_INBOX_NOT_EMPTY",
                            "Scanner simulator inbox already contains a PDF from before this session. Remove or move it, click Start Scan, then select/stage the test PDF.",
                            HttpStatus.CONFLICT);
                }
            }
        } catch (ScanBridgeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ScanBridgeException("SCANNER_INBOX_UNAVAILABLE", "Scanner inbox is unavailable.", HttpStatus.SERVICE_UNAVAILABLE);
        }
    }

    /**
     * QA-friendly simulator acquisition. The upload is written as a durable .part file then atomically
     * published into the exact same inbox consumed by the normal stable-file scanner path.
     */
    public synchronized void stageTestPdf(UUID sessionId, String originalFileName, InputStream content) {
        if (properties.getMode() != ScannerCaptureMode.SIMULATOR) {
            throw new ScanBridgeException("SIMULATOR_NOT_ENABLED", "Test PDF acquisition is available only in SIMULATOR mode.", HttpStatus.CONFLICT);
        }
        ScanSession waiting = sessions.activeWaitingSession().orElseThrow(() ->
                new ScanBridgeException("SCAN_SESSION_NOT_WAITING", "No scan session is waiting for a test PDF.", HttpStatus.CONFLICT));
        if (!waiting.sessionId().equals(sessionId)) {
            throw new ScanBridgeException("SCAN_SESSION_NOT_WAITING", "The requested scan session is not the active waiting session.", HttpStatus.CONFLICT);
        }
        storageHealth.assertReadyForNewScan();
        String safeName = safeFileName(originalFileName);
        if (!safeName.toLowerCase().endsWith(".pdf")) {
            throw new ScanBridgeException("UNSUPPORTED_FILE_TYPE", "Scanner Intake accepts PDF files only.", HttpStatus.BAD_REQUEST);
        }
        try {
            ensureDirectories();
            try (var stream = Files.list(inboxPath())) {
                if (stream.filter(Files::isRegularFile).anyMatch(path -> !path.getFileName().toString().endsWith(".part"))) {
                    throw new ScanBridgeException("SCANNER_INBOX_NOT_EMPTY", "Scanner simulator inbox already contains a PDF. Remove or move it before staging the test PDF for this session.", HttpStatus.CONFLICT);
                }
            }
            Path finalPath = uniqueTarget(inboxPath(), safeName);
            Path part = finalPath.resolveSibling(finalPath.getFileName() + "." + UUID.randomUUID() + ".part");
            try {
                try (FileChannel channel = FileChannel.open(part, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
                    byte[] buffer = new byte[64 * 1024];
                    int read;
                    long total = 0;
                    while ((read = content.read(buffer)) >= 0) {
                        if (read <= 0) continue;
                        total = Math.addExact(total, read);
                        storageHealth.assertIncomingSize(total);
                        channel.write(ByteBuffer.wrap(buffer, 0, read));
                    }
                    channel.force(true);
                }
                if (Files.size(part) < 1) {
                    throw new ScanBridgeException("EMPTY_SCAN", "Selected test PDF is empty.", HttpStatus.BAD_REQUEST);
                }
                move(part, finalPath);
                tracker.forget(finalPath);
            } catch (Exception ex) {
                Files.deleteIfExists(part);
                throw ex;
            }
            log.info("SCAN_BRIDGE_SIMULATOR_FILE_STAGED scannerId={} sessionId={} sizeBytes={}",
                    properties.getScannerId(), sessionId, Files.size(finalPath));
        } catch (ScanBridgeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ScanBridgeException("SIMULATOR_STAGE_FAILED", "Unable to stage the test PDF for scanner simulation.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
    }

    private void quarantineIncompleteSimulatorUploads() throws IOException {
        List<Path> incomplete;
        try (var stream = Files.list(inboxPath())) {
            incomplete = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().endsWith(".part"))
                    .toList();
        }
        for (Path path : incomplete) {
            moveToProblem(path, "INCOMPLETE_SIMULATOR_UPLOAD");
        }
    }

    public Path inboxPath() { return Path.of(properties.getSimulator().getInboxPath()).toAbsolutePath().normalize(); }
    public Path workPath() { return Path.of(properties.getWorkPath()).toAbsolutePath().normalize(); }
    public Path problemPath() { return Path.of(properties.getProblemPath()).toAbsolutePath().normalize(); }

    private void recoverClaimedPdfForWaitingSession() throws IOException {
        var waiting = sessions.activeWaitingSession();
        if (waiting.isEmpty()) return;
        Path sessionDirectory = workPath().resolve(waiting.get().sessionId().toString()).normalize();
        if (!Files.isDirectory(sessionDirectory)) return;
        List<Path> pdfs;
        try (var stream = Files.list(sessionDirectory)) {
            pdfs = stream.filter(Files::isRegularFile)
                    .filter(path -> path.getFileName().toString().toLowerCase().endsWith(".pdf"))
                    .toList();
        }
        if (pdfs.isEmpty()) return;
        if (pdfs.size() != 1) {
            throw new IllegalStateException("Waiting scanner session has multiple claimed PDF artifacts");
        }
        Path target = pdfs.get(0);
        if (!hasPdfMagic(target)) throw new IllegalStateException("Recovered scanner PDF is invalid");
        BasicFileAttributes attributes = Files.readAttributes(target, BasicFileAttributes.class);
        PreparedScan prepared = preparedScan(target, attributes);
        sessions.markReady(waiting.get().sessionId(), prepared);
        log.info("SCAN_BRIDGE_CLAIM_RECOVERED scannerId={} sessionId={} sourceDocumentId={} sizeBytes={}",
                properties.getScannerId(), waiting.get().sessionId(), prepared.sourceDocumentId(), prepared.sizeBytes());
    }

    private void claimForSession(Path source, BasicFileAttributes observed, ScanSession session) throws IOException {
        Path sessionDirectory = workPath().resolve(session.sessionId().toString());
        Files.createDirectories(sessionDirectory);
        Path target = sessionDirectory.resolve(source.getFileName().toString());
        if (Files.exists(target)) throw new IOException("Prepared scan target already exists for session");
        move(source, target);
        tracker.forget(source);
        BasicFileAttributes claimed = Files.readAttributes(target, BasicFileAttributes.class);
        if (claimed.size() != observed.size() || claimed.lastModifiedTime().toMillis() != observed.lastModifiedTime().toMillis()) {
            Path restored = uniqueTarget(inboxPath(), target.getFileName().toString());
            move(target, restored);
            throw new IOException("Scanner file changed while it was being claimed");
        }
        PreparedScan prepared = preparedScan(target, claimed);
        try {
            sessions.markReady(session.sessionId(), prepared);
        } catch (RuntimeException ex) {
            Path restored = uniqueTarget(inboxPath(), target.getFileName().toString());
            move(target, restored);
            tracker.forget(restored);
            throw ex;
        }
        log.info("SCAN_BRIDGE_SCAN_READY bridgeId={} siteId={} scannerId={} sessionId={} submissionId={} sourceDocumentId={} sizeBytes={}",
                properties.getBridgeId(), properties.getSiteId(), properties.getScannerId(), session.sessionId(), session.submissionId(),
                prepared.sourceDocumentId(), prepared.sizeBytes());
    }

    private PreparedScan preparedScan(Path target, BasicFileAttributes attributes) throws IOException {
        return new PreparedScan(UUID.randomUUID(), target.getFileName().toString(), attributes.size(), sha256(target),
                clock.instant(), workPath().relativize(target).toString().replace('\\', '/'), null);
    }

    private void moveToProblem(Path source, String reasonCode) throws IOException {
        Path target = uniqueTarget(problemPath(), source.getFileName().toString());
        move(source, target);
        tracker.forget(source);
        Files.writeString(target.resolveSibling(target.getFileName() + ".reason.txt"), reasonCode + System.lineSeparator(), StandardOpenOption.CREATE_NEW);
        log.warn("SCAN_BRIDGE_FILE_REJECTED scannerId={} reasonCode={}", properties.getScannerId(), reasonCode);
    }

    private String safeFileName(String originalFileName) {
        String name = originalFileName == null ? "scan.pdf" : Path.of(originalFileName).getFileName().toString().trim();
        name = name.replaceAll("[\\p{Cntrl}]", "");
        if (name.isBlank()) name = "scan.pdf";
        if (name.length() > 180) {
            int dot = name.toLowerCase().lastIndexOf(".pdf");
            name = (dot > 0 ? name.substring(0, Math.min(dot, 176)) : name.substring(0, 176)) + ".pdf";
        }
        return name;
    }

    private Path uniqueTarget(Path directory, String originalName) {
        Path candidate = directory.resolve(originalName);
        if (!Files.exists(candidate)) return candidate;
        int dot = originalName.lastIndexOf('.');
        String base = dot > 0 ? originalName.substring(0, dot) : originalName;
        String ext = dot > 0 ? originalName.substring(dot) : "";
        return directory.resolve(base + "-" + UUID.randomUUID() + ext);
    }

    private void move(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException ex) {
            throw new IOException("Atomic move is required for Scanner Bridge crash consistency", ex);
        }
    }

    private boolean hasPdfMagic(Path path) throws IOException {
        int max = (int) Math.min(1024, Files.size(path));
        if (max < PDF_MAGIC.length) return false;
        byte[] prefix = new byte[max];
        try (InputStream input = Files.newInputStream(path)) {
            int offset = 0;
            while (offset < prefix.length) {
                int read = input.read(prefix, offset, prefix.length - offset);
                if (read < 0) break;
                offset += read;
            }
        }
        outer:
        for (int i = 0; i <= prefix.length - PDF_MAGIC.length; i++) {
            for (int j = 0; j < PDF_MAGIC.length; j++) {
                if (prefix[i + j] != PDF_MAGIC[j]) continue outer;
            }
            return true;
        }
        return false;
    }

    private String sha256(Path path) throws IOException {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            try (InputStream input = Files.newInputStream(path)) {
                byte[] buffer = new byte[64 * 1024];
                int read;
                while ((read = input.read(buffer)) >= 0) if (read > 0) digest.update(buffer, 0, read);
            }
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 is not available", ex);
        }
    }

    private long lastModifiedSafe(Path path) {
        try { return Files.getLastModifiedTime(path).toMillis(); }
        catch (IOException ex) { return Long.MAX_VALUE; }
    }
}
