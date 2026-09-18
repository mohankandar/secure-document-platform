package com.securedoc.platform.scanbridge.service;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.PreparedScan;
import com.securedoc.platform.scanbridge.model.ScanSession;
import com.securedoc.platform.scanbridge.model.ScanSessionStatus;
import com.securedoc.platform.scanbridge.model.ScannerCaptureMode;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * Windows WIA physical acquisition adapter.
 *
 * <p>Only acquisition is hardware-specific. Once a consolidated PDF is durably published beneath
 * the session work directory, the existing Scanner Intake queue/delivery/lifecycle is unchanged.</p>
 */
@Component
public class PhysicalScannerAcquisitionAdapter implements ScannerAcquisitionAdapter {
    private static final Logger log = LoggerFactory.getLogger(PhysicalScannerAcquisitionAdapter.class);
    private static final byte[] PDF_MAGIC = new byte[] {'%', 'P', 'D', 'F', '-'};

    private final ScanBridgeProperties properties;
    private final ScanSessionService sessions;
    private final FileScanSessionStore store;
    private final ScannerStorageHealthService storageHealth;
    private final WindowsWiaScannerClient wia;
    private final PhysicalScanPdfAssembler pdfAssembler;

    public PhysicalScannerAcquisitionAdapter(ScanBridgeProperties properties,
                                             ScanSessionService sessions,
                                             FileScanSessionStore store,
                                             ScannerStorageHealthService storageHealth,
                                             WindowsWiaScannerClient wia,
                                             PhysicalScanPdfAssembler pdfAssembler) {
        this.properties = properties;
        this.sessions = sessions;
        this.store = store;
        this.storageHealth = storageHealth;
        this.wia = wia;
        this.pdfAssembler = pdfAssembler;
    }

    @Override
    public ScannerCaptureMode mode() { return ScannerCaptureMode.PHYSICAL; }

    @Override
    public boolean available() {
        return properties.isEnabled() && properties.getMode() == ScannerCaptureMode.PHYSICAL && wia.available();
    }

    @Override
    public void assertReadyForNewSession() {
        if (properties.getMode() != ScannerCaptureMode.PHYSICAL) {
            throw new ScanBridgeException("PHYSICAL_SCANNER_NOT_ENABLED",
                    "Physical scanner mode is not enabled.", HttpStatus.CONFLICT);
        }
        wia.assertAvailable();
    }

    @Override
    public synchronized void acquire(ScanSession session) {
        if (session == null || session.captureMode() != ScannerCaptureMode.PHYSICAL) {
            throw new ScanBridgeException("PHYSICAL_SCAN_SESSION_INVALID",
                    "Physical acquisition requires a physical scan session.", HttpStatus.CONFLICT);
        }
        ScanSession waiting = sessions.activeWaitingSession().orElseThrow(() ->
                new ScanBridgeException("SCAN_SESSION_NOT_WAITING",
                        "No physical scan session is waiting for a document.", HttpStatus.CONFLICT));
        if (!waiting.sessionId().equals(session.sessionId())) {
            throw new ScanBridgeException("SCAN_SESSION_NOT_WAITING",
                    "The requested physical scan session is not the active waiting session.", HttpStatus.CONFLICT);
        }

        storageHealth.assertReadyForNewScan();
        Path sessionDirectory = store.sessionDirectory(session.sessionId());
        Path captureDirectory = sessionDirectory.resolve("physical-capture").normalize();
        Path finalPdf = sessionDirectory.resolve("physical-scan-" + session.submissionId() + ".pdf").normalize();
        if (!captureDirectory.startsWith(sessionDirectory) || !finalPdf.startsWith(sessionDirectory)) {
            throw new IllegalStateException("Physical scanner path escaped its session directory");
        }

        boolean ready = false;
        try {
            Files.createDirectories(sessionDirectory);
            if (Files.exists(finalPdf)) {
                throw new ScanBridgeException("PHYSICAL_SCAN_ARTIFACT_ALREADY_EXISTS",
                        "A physical scan artifact already exists for this session.", HttpStatus.CONFLICT);
            }
            deleteTree(captureDirectory);
            Files.createDirectories(captureDirectory);

            log.info("SCAN_BRIDGE_PHYSICAL_ACQUISITION_STARTED scannerId={} sessionId={} provider=WIA",
                    properties.getScannerId(), session.sessionId());
            List<Path> images = wia.acquire(captureDirectory);
            int pageCount = pdfAssembler.assemble(images, finalPdf, properties.getPhysical().getDpi());
            long sizeBytes = Files.size(finalPdf);
            storageHealth.assertIncomingSize(sizeBytes);
            if (!hasPdfMagic(finalPdf)) throw new IOException("Consolidated physical scan is not a PDF");

            PreparedScan prepared = new PreparedScan(UUID.randomUUID(), finalPdf.getFileName().toString(), sizeBytes,
                    sha256(finalPdf), Instant.now(), portableWorkReference(finalPdf), pageCount);
            sessions.markReady(session.sessionId(), prepared);
            ready = true;
            log.info("SCAN_BRIDGE_PHYSICAL_SCAN_READY scannerId={} sessionId={} sourceDocumentId={} pageCount={} sizeBytes={}",
                    properties.getScannerId(), session.sessionId(), prepared.sourceDocumentId(), pageCount, sizeBytes);
        } catch (ScanBridgeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ScanBridgeException("PHYSICAL_SCAN_FINALIZATION_FAILED",
                    "The physical scan could not be finalized safely.", HttpStatus.INTERNAL_SERVER_ERROR);
        } finally {
            if (!ready) {
                try { Files.deleteIfExists(finalPdf); }
                catch (IOException ex) {
                    log.warn("SCAN_BRIDGE_PHYSICAL_FAILED_PDF_CLEANUP_FAILED scannerId={} sessionId={}",
                            properties.getScannerId(), session.sessionId());
                }
            }
            try { deleteTree(captureDirectory); }
            catch (RuntimeException ex) {
                log.warn("SCAN_BRIDGE_PHYSICAL_TEMP_CLEANUP_FAILED scannerId={} sessionId={} exceptionType={}",
                        properties.getScannerId(), session.sessionId(), ex.getClass().getSimpleName());
            }
        }
    }

    /**
     * Crash-window recovery: if the final PDF was atomically published but the process died before
     * the durable READY transition, reconstruct PreparedScan and resume without reacquiring paper.
     */
    @PostConstruct
    public synchronized void recoverPublishedPhysicalScan() {
        if (properties.getMode() != ScannerCaptureMode.PHYSICAL) return;
        var actionable = sessions.currentActionableSession();
        if (actionable.isEmpty() || actionable.get().status() != ScanSessionStatus.WAITING_FOR_SCAN) return;
        ScanSession session = actionable.get();
        Path directory = store.sessionDirectory(session.sessionId());
        if (!Files.isDirectory(directory)) return;
        try {
            List<Path> pdfs;
            try (var stream = Files.list(directory)) {
                pdfs = stream.filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".pdf"))
                        .toList();
            }
            if (pdfs.isEmpty()) return;
            if (pdfs.size() != 1) throw new IllegalStateException("Physical scanner session has multiple finalized PDFs");
            Path pdf = pdfs.get(0);
            if (!hasPdfMagic(pdf)) throw new IllegalStateException("Recovered physical scanner PDF is invalid");
            long sizeBytes = Files.size(pdf);
            storageHealth.assertIncomingSize(sizeBytes);
            PreparedScan prepared = new PreparedScan(UUID.randomUUID(), pdf.getFileName().toString(), sizeBytes,
                    sha256(pdf), Instant.now(), portableWorkReference(pdf), null);
            sessions.markReady(session.sessionId(), prepared);
            deleteTree(directory.resolve("physical-capture"));
            log.info("SCAN_BRIDGE_PHYSICAL_SCAN_RECOVERED scannerId={} sessionId={} sourceDocumentId={} sizeBytes={}",
                    properties.getScannerId(), session.sessionId(), prepared.sourceDocumentId(), sizeBytes);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to recover a finalized physical scanner PDF", ex);
        }
    }

    private String portableWorkReference(Path path) {
        Path normalized = path.toAbsolutePath().normalize();
        if (!normalized.startsWith(store.workRoot())) throw new IllegalArgumentException("Physical scan escaped work root");
        return store.workRoot().relativize(normalized).toString().replace('\\', '/');
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

    private void deleteTree(Path directory) {
        if (directory == null || !Files.exists(directory)) return;
        try (var paths = Files.walk(directory)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
        } catch (IOException ex) {
            throw new IllegalStateException("Unable to remove physical scanner temporary files", ex);
        }
    }
}
