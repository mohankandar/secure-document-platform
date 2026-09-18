package com.securedoc.platform.scanbridge.api;

import com.securedoc.platform.scanbridge.config.ScanBridgeProperties;
import com.securedoc.platform.scanbridge.model.ScanBridgeConfigurationResponse;
import com.securedoc.platform.scanbridge.model.ScanSession;
import com.securedoc.platform.scanbridge.model.ScanSessionStatus;
import com.securedoc.platform.scanbridge.model.ScannerCaptureMode;
import com.securedoc.platform.scanbridge.model.ScannerStatusResponse;
import com.securedoc.platform.scanbridge.model.ScannerDeliveryItemResponse;
import com.securedoc.platform.scanbridge.model.StartScanSessionRequest;
import com.securedoc.platform.scanbridge.service.ScanBridgeException;
import com.securedoc.platform.scanbridge.service.ScanSessionService;
import com.securedoc.platform.scanbridge.service.BridgeRuntimeIdentity;
import com.securedoc.platform.scanbridge.service.ScannerStorageHealthService;
import com.securedoc.platform.scanbridge.service.ScannerAcquisitionRouter;
import com.securedoc.platform.scanbridge.service.ScannerSimulatorService;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1")
public class ScanBridgeController {
    private static final List<String> PROHIBITED_CAPABILITIES = List.of(
            "OCR", "AI_EXTRACTION", "BARCODE_EXTRACTION", "QR_EXTRACTION",
            "DOCUMENT_CLASSIFICATION", "BUSINESS_METADATA_INFERENCE"
    );

    private final ScanBridgeProperties properties;
    private final ScanSessionService sessions;
    private final ScannerAcquisitionRouter acquisition;
    private final ScannerSimulatorService simulator;
    private final BridgeRuntimeIdentity runtimeIdentity;
    private final ScannerStorageHealthService storageHealth;

    public ScanBridgeController(ScanBridgeProperties properties, ScanSessionService sessions,
                                ScannerAcquisitionRouter acquisition, ScannerSimulatorService simulator,
                                BridgeRuntimeIdentity runtimeIdentity, ScannerStorageHealthService storageHealth) {
        this.properties = properties;
        this.sessions = sessions;
        this.acquisition = acquisition;
        this.simulator = simulator;
        this.runtimeIdentity = runtimeIdentity;
        this.storageHealth = storageHealth;
    }

    @GetMapping("/configuration")
    public ScanBridgeConfigurationResponse configuration() {
        return new ScanBridgeConfigurationResponse(
                properties.isEnabled(), properties.getTenantId(), properties.getBridgeId(), properties.getSiteId(),
                properties.getScannerId(), properties.getMode(), runtimeIdentity.instanceId(), runtimeIdentity.applicationVersion(),
                runtimeIdentity.browserRequestToken(),
                properties.getMode() == ScannerCaptureMode.SIMULATOR ? simulator.inboxPath().toString() : null,
                properties.getSimulator().getStableWindowMs(), storageHealth.maxScanPdfSizeBytes(),
                Math.max(1, properties.getStorage().getMinimumUsableSpaceBytes()), PROHIBITED_CAPABILITIES);
    }

    @GetMapping("/scanner-status")
    public ScannerStatusResponse scannerStatus() {
        UUID active = sessions.activeSessionId();
        var storage = storageHealth.snapshot();
        return new ScannerStatusResponse(
                properties.isEnabled(), properties.getBridgeId(), properties.getSiteId(), properties.getScannerId(),
                properties.getMode(), runtimeIdentity.instanceId(), runtimeIdentity.applicationVersion(),
                acquisition.available() && storage.healthy(), storage.healthy(), storage.usableSpaceBytes(),
                storage.minimumUsableSpaceBytes(), storage.maxScanPdfSizeBytes(), active != null, active,
                Math.toIntExact(Math.min(Integer.MAX_VALUE, sessions.count(ScanSessionStatus.QUEUED)
                        + sessions.count(ScanSessionStatus.SUBMITTING))),
                Math.toIntExact(Math.min(Integer.MAX_VALUE, sessions.count(ScanSessionStatus.ATTENTION_REQUIRED))),
                Instant.now());
    }

    @PostMapping("/scan-sessions")
    public ResponseEntity<ScanSession> start(@RequestBody(required = false) StartScanSessionRequest request) {
        acquisition.assertReadyForNewSession();
        ScanSession session = sessions.start(request);
        try {
            acquisition.acquire(session);
            return ResponseEntity.status(HttpStatus.CREATED).body(sessions.get(session.sessionId()));
        } catch (RuntimeException ex) {
            // Physical acquisition happens only after durable session creation. If the operator
            // cancels or the device fails before a PDF becomes READY, remove that empty session so
            // the workstation is immediately safe to retry.
            try {
                sessions.cancel(session.sessionId());
            } catch (RuntimeException ignored) {
                // Preserve the original acquisition failure. Durable recovery will still expose
                // any state that could not be cancelled.
            }
            throw ex;
        }
    }

    @GetMapping("/scan-sessions/{sessionId}")
    public ScanSession get(@PathVariable UUID sessionId) {
        return sessions.get(sessionId);
    }

    /**
     * Browser/tab recovery endpoint. Only WAITING_FOR_SCAN or READY is returned; queued delivery
     * continues independently and must never block the operator from starting the next package.
     */
    @GetMapping("/scan-sessions/current")
    public ResponseEntity<ScanSession> current() {
        return sessions.currentActionableSession()
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/scan-sessions/{sessionId}/cancel")
    public ScanSession cancel(@PathVariable UUID sessionId) {
        return sessions.cancel(sessionId);
    }

    /** Operator handoff point: persist QUEUED locally and return without waiting for LexaVault processing. */
    @PostMapping("/scan-sessions/{sessionId}/submit")
    public ResponseEntity<ScanSession> submit(@PathVariable UUID sessionId) {
        return ResponseEntity.accepted().body(sessions.queueForSubmission(sessionId));
    }

    /** Privacy-safe local delivery diagnostics for support/validation. */
    @GetMapping("/delivery-items")
    public List<ScannerDeliveryItemResponse> deliveryItems() {
        return sessions.deliveryDiagnostics();
    }

    /** Requeue the same retained ATTENTION_REQUIRED package after the underlying issue is corrected. */
    @PostMapping("/delivery-items/{sessionId}/retry")
    public ResponseEntity<ScanSession> retryDelivery(@PathVariable UUID sessionId) {
        return ResponseEntity.accepted().body(sessions.retryAttentionRequired(sessionId));
    }

    /**
     * Scanner-less QA acquisition. Available only when the bridge was explicitly started in SIMULATOR mode.
     * The selected PDF is published to the same stable-file acquisition path as a simulated device output.
     */
    @PostMapping(value = "/scan-sessions/{sessionId}/simulator-pdf", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<Void> simulatorPdf(@PathVariable UUID sessionId, @RequestParam("file") MultipartFile file) {
        if (file == null || file.isEmpty()) {
            throw new ScanBridgeException("EMPTY_SCAN", "Selected test PDF is empty.", HttpStatus.BAD_REQUEST);
        }
        try (var input = file.getInputStream()) {
            simulator.stageTestPdf(sessionId, file.getOriginalFilename(), input);
        } catch (ScanBridgeException ex) {
            throw ex;
        } catch (IOException ex) {
            throw new ScanBridgeException("SIMULATOR_STAGE_FAILED", "Unable to read the selected test PDF.", HttpStatus.INTERNAL_SERVER_ERROR);
        }
        return ResponseEntity.accepted().build();
    }
}
