package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.manual.command.AddManualRedactionRegionCommand;
import com.securedoc.platform.application.manual.command.ManualFallbackCommand;
import com.securedoc.platform.application.manual.command.RemoveManualRedactionRegionCommand;
import com.securedoc.platform.application.manual.command.SubmitManualFallbackCommand;
import com.securedoc.platform.application.manual.result.ManualFallbackDetail;
import com.securedoc.platform.application.manual.result.ManualRedactionRegionSummary;
import com.securedoc.platform.application.port.in.AddManualRedactionRegionUseCase;
import com.securedoc.platform.application.port.in.GetManualFallbackPagePreviewQuery;
import com.securedoc.platform.application.port.in.GetManualFallbackQuery;
import com.securedoc.platform.application.port.in.RemoveManualRedactionRegionUseCase;
import com.securedoc.platform.application.port.in.StartManualFallbackUseCase;
import com.securedoc.platform.application.port.in.SubmitManualFallbackUseCase;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.FindingId;
import com.securedoc.platform.domain.identity.PackageId;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** Business continuity workflow used only after safe automatic AI recovery is exhausted. */
@RestController
@RequestMapping("/api/v1/packages/{packageId}/manual-fallback")
public class ManualFallbackController {
    private final StartManualFallbackUseCase startUseCase;
    private final GetManualFallbackQuery query;
    private final AddManualRedactionRegionUseCase addRegionUseCase;
    private final RemoveManualRedactionRegionUseCase removeRegionUseCase;
    private final GetManualFallbackPagePreviewQuery previewQuery;
    private final SubmitManualFallbackUseCase submitUseCase;
    private final RequestContextResolver contextResolver;

    public ManualFallbackController(StartManualFallbackUseCase startUseCase,
                                    GetManualFallbackQuery query,
                                    AddManualRedactionRegionUseCase addRegionUseCase,
                                    RemoveManualRedactionRegionUseCase removeRegionUseCase,
                                    GetManualFallbackPagePreviewQuery previewQuery,
                                    SubmitManualFallbackUseCase submitUseCase,
                                    RequestContextResolver contextResolver) {
        this.startUseCase = startUseCase;
        this.query = query;
        this.addRegionUseCase = addRegionUseCase;
        this.removeRegionUseCase = removeRegionUseCase;
        this.previewQuery = previewQuery;
        this.submitUseCase = submitUseCase;
        this.contextResolver = contextResolver;
    }

    @GetMapping("/eligibility")
    @RequireCapability(UserCapability.MANUAL_REVIEW)
    public ResponseEntity<Map<String, Object>> eligibility(@PathVariable String packageId,
                                                            HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        try {
            startUseCase.validateEligibility(new ManualFallbackCommand(
                    packageId(packageId), principal.tenantId(), principal.actorId(),
                    contextResolver.resolveCorrelationId(request)));
            return ResponseEntity.ok(Map.of("eligible", true, "reason", ""));
        } catch (com.securedoc.platform.application.exception.ValidationException notEligible) {
            return ResponseEntity.ok(Map.of("eligible", false, "reason", notEligible.getMessage()));
        }
    }

    @PostMapping("/start")
    @RequireCapability(UserCapability.MANUAL_REVIEW)
    public ResponseEntity<ManualFallbackResponse> start(@PathVariable String packageId,
                                                        HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var detail = startUseCase.execute(new ManualFallbackCommand(
                packageId(packageId), principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request)));
        return ResponseEntity.ok(ManualFallbackResponse.from(detail));
    }

    @GetMapping
    @RequireCapability(UserCapability.MANUAL_REVIEW)
    public ResponseEntity<ManualFallbackResponse> detail(@PathVariable String packageId,
                                                         HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        return ResponseEntity.ok(ManualFallbackResponse.from(
                query.execute(packageId(packageId), principal.tenantId())));
    }

    @GetMapping("/documents/{documentId}/pages/{pageNumber}/preview")
    @RequireCapability(UserCapability.MANUAL_REVIEW)
    public ResponseEntity<byte[]> preview(@PathVariable String packageId,
                                          @PathVariable String documentId,
                                          @PathVariable int pageNumber,
                                          HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        var result = previewQuery.execute(packageId(packageId), documentId(documentId), pageNumber,
                principal.tenantId(), principal.actorId(), contextResolver.resolveCorrelationId(request));
        return ResponseEntity.ok()
                .cacheControl(CacheControl.noStore())
                .contentType(MediaType.parseMediaType(result.mimeType()))
                .header("X-Manual-Page-Number", Integer.toString(result.pageNumber()))
                .header("X-Page-Width", Integer.toString(result.pixelWidth()))
                .header("X-Page-Height", Integer.toString(result.pixelHeight()))
                .body(result.content());
    }

    @PostMapping("/regions")
    @RequireCapability(UserCapability.REDACTION_ADJUST)
    public ResponseEntity<ManualRegionResponse> addRegion(@PathVariable String packageId,
                                                          @RequestBody AddRegionRequest body,
                                                          HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        BoundingBoxRequest box = body.boundingBox();
        if (box == null) throw new IllegalArgumentException("boundingBox is required");
        ManualRedactionRegionSummary result = addRegionUseCase.execute(new AddManualRedactionRegionCommand(
                packageId(packageId), documentId(body.documentId()), body.pageNumber(),
                EntityType.valueOf(body.entityType()), box.left(), box.top(), box.width(), box.height(),
                principal.tenantId(), principal.actorId(), contextResolver.resolveCorrelationId(request)));
        return ResponseEntity.ok(ManualRegionResponse.from(result));
    }

    @DeleteMapping("/regions/{findingId}")
    @RequireCapability(UserCapability.REDACTION_ADJUST)
    public ResponseEntity<Map<String, String>> removeRegion(@PathVariable String packageId,
                                                            @PathVariable String findingId,
                                                            HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        removeRegionUseCase.execute(new RemoveManualRedactionRegionCommand(
                packageId(packageId), FindingId.of(UUID.fromString(findingId)), principal.tenantId(),
                principal.actorId(), contextResolver.resolveCorrelationId(request)));
        return ResponseEntity.ok(Map.of("status", "REMOVED"));
    }

    @PostMapping("/submit")
    @RequireCapability(UserCapability.REVIEW_SUBMIT)
    public ResponseEntity<Map<String, String>> submit(@PathVariable String packageId,
                                                      @RequestBody SubmitRequest body,
                                                      HttpServletRequest request) {
        var principal = contextResolver.resolvePrincipal(request);
        submitUseCase.execute(new SubmitManualFallbackCommand(
                packageId(packageId), principal.tenantId(), principal.actorId(),
                contextResolver.resolveCorrelationId(request), body.reviewAttested()));
        return ResponseEntity.accepted().body(Map.of("packageId", packageId, "status", "PROCESSING"));
    }

    private static PackageId packageId(String value) {
        return PackageId.of(UUID.fromString(value));
    }

    private static DocumentId documentId(String value) {
        return DocumentId.of(UUID.fromString(value));
    }

    public record BoundingBoxRequest(double left, double top, double width, double height) {}
    public record AddRegionRequest(String documentId, int pageNumber, String entityType, BoundingBoxRequest boundingBox) {}
    public record SubmitRequest(boolean reviewAttested) {}

    public record ManualFallbackDocumentResponse(String documentId, String fileName, int pageCount) {}
    public record ManualRegionResponse(String findingId, String documentId, int pageNumber, String entityType,
                                       double left, double top, double width, double height) {
        static ManualRegionResponse from(ManualRedactionRegionSummary region) {
            return new ManualRegionResponse(region.findingId().toString(), region.documentId().toString(),
                    region.pageNumber(), region.entityType().name(), region.left(), region.top(),
                    region.width(), region.height());
        }
    }
    public record ManualFallbackResponse(String packageId, String status,
                                         List<ManualFallbackDocumentResponse> documents,
                                         List<ManualRegionResponse> regions,
                                         Set<String> enabledEntityTypes) {
        static ManualFallbackResponse from(ManualFallbackDetail detail) {
            return new ManualFallbackResponse(detail.packageId().toString(), detail.status(),
                    detail.documents().stream().map(d -> new ManualFallbackDocumentResponse(
                            d.documentId().toString(), d.fileName(), d.pageCount())).toList(),
                    detail.regions().stream().map(ManualRegionResponse::from).toList(),
                    detail.enabledEntityTypes().stream().map(Enum::name)
                            .collect(java.util.stream.Collectors.toUnmodifiableSet()));
        }
    }
}
