package com.securedoc.platform.api.controller;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.dto.CreatePackageRequest;
import com.securedoc.platform.api.dto.CreatePackageResponse;
import com.securedoc.platform.api.dto.DocumentSummaryResponse;
import com.securedoc.platform.api.dto.PackageDetailResponse;
import com.securedoc.platform.api.dto.PackageSummaryResponse;
import com.securedoc.platform.api.dto.SubmitPackageResponse;
import com.securedoc.platform.api.dto.UploadDocumentResponse;
import com.securedoc.platform.api.upload.MultipartUploadContent;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.command.CreatePackageCommand;
import com.securedoc.platform.application.intake.command.CreatePackageResult;
import com.securedoc.platform.application.intake.command.SubmitPackageCommand;
import com.securedoc.platform.application.intake.command.UploadDocumentCommand;
import com.securedoc.platform.application.intake.command.UploadDocumentResult;
import com.securedoc.platform.application.intake.query.PackageDetail;
import com.securedoc.platform.application.intake.query.PackageSummary;
import com.securedoc.platform.application.port.in.CreatePackageUseCase;
import com.securedoc.platform.application.port.in.GetPackageQuery;
import com.securedoc.platform.application.port.in.ListPackagesQuery;
import com.securedoc.platform.application.port.in.SubmitPackageUseCase;
import com.securedoc.platform.application.port.in.UploadDocumentUseCase;
import com.securedoc.platform.domain.document.PackageType;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.CorrelationId;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/packages")
public class PackageController {

    private final CreatePackageUseCase createPackageUseCase;
    private final UploadDocumentUseCase uploadDocumentUseCase;
    private final SubmitPackageUseCase submitPackageUseCase;
    private final GetPackageQuery getPackageQuery;
    private final ListPackagesQuery listPackagesQuery;
    private final RequestContextResolver contextResolver;

    public PackageController(CreatePackageUseCase createPackageUseCase,
                              UploadDocumentUseCase uploadDocumentUseCase,
                              SubmitPackageUseCase submitPackageUseCase,
                              GetPackageQuery getPackageQuery,
                              ListPackagesQuery listPackagesQuery,
                              RequestContextResolver contextResolver) {
        this.createPackageUseCase = createPackageUseCase;
        this.uploadDocumentUseCase = uploadDocumentUseCase;
        this.submitPackageUseCase = submitPackageUseCase;
        this.getPackageQuery = getPackageQuery;
        this.listPackagesQuery = listPackagesQuery;
        this.contextResolver = contextResolver;
    }

    @PostMapping
    @Transactional
    public ResponseEntity<CreatePackageResponse> createPackage(
            @Valid @RequestBody CreatePackageRequest request,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        PackageType packageType = PackageType.valueOf(request.packageType());

        CreatePackageCommand command = CreatePackageCommand.of(packageType, request.metadata(), correlationId);
        CreatePackageResult result = createPackageUseCase.execute(command, principal);

        CreatePackageResponse response = new CreatePackageResponse(
                result.packageId().toString(),
                result.status().name()
        );
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping(value = "/{packageId}/documents", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @Transactional
    public ResponseEntity<UploadDocumentResponse> uploadDocument(
            @PathVariable String packageId,
            @RequestParam("file") MultipartFile file,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        PackageId pkgId = PackageId.of(UUID.fromString(packageId));

        try (MultipartUploadContent content = MultipartUploadContent.from(file)) {
            String originalFilename = file.getOriginalFilename() != null ? file.getOriginalFilename() : "document.pdf";
            String contentType = file.getContentType() != null ? file.getContentType() : "application/pdf";

            UploadDocumentCommand command = UploadDocumentCommand.of(
                    pkgId, originalFilename, contentType, content, correlationId);

            UploadDocumentResult result = uploadDocumentUseCase.execute(command, principal);

            UploadDocumentResponse response = new UploadDocumentResponse(
                    result.documentId().toString(),
                    result.artifactId().toString(),
                    result.status().name()
            );
            return ResponseEntity.status(HttpStatus.CREATED).body(response);
        }
    }

    @PostMapping("/{packageId}/submit")
    @Transactional
    public ResponseEntity<SubmitPackageResponse> submitPackage(
            @PathVariable String packageId,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        CorrelationId correlationId = contextResolver.resolveCorrelationId(httpRequest);
        PackageId pkgId = PackageId.of(UUID.fromString(packageId));

        SubmitPackageCommand command = SubmitPackageCommand.of(pkgId, correlationId);
        submitPackageUseCase.execute(command, principal);

        SubmitPackageResponse response = new SubmitPackageResponse(
                packageId, "SUBMITTED"
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping("/{packageId}")
    @Transactional(readOnly = true)
    public ResponseEntity<PackageDetailResponse> getPackageDetail(
            @PathVariable String packageId,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);
        PackageId pkgId = PackageId.of(UUID.fromString(packageId));

        PackageDetail detail = getPackageQuery.execute(pkgId, principal);

        List<DocumentSummaryResponse> docs = detail.documents().stream()
                .map(d -> new DocumentSummaryResponse(
                        d.documentId().toString(),
                        d.fileName(),
                        d.mimeType(),
                        d.sizeBytes(),
                        d.documentType().name(),
                        d.status().name()
                ))
                .toList();

        PackageDetailResponse response = new PackageDetailResponse(
                detail.packageId().toString(),
                detail.packageType().name(),
                detail.status().name(),
                detail.metadata(),
                docs,
                detail.createdAt(),
                detail.updatedAt()
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping
    @Transactional(readOnly = true)
    public ResponseEntity<List<PackageSummaryResponse>> listPackages(
            @RequestParam(defaultValue = "0") int offset,
            @RequestParam(defaultValue = "20") int limit,
            HttpServletRequest httpRequest) {

        AuthenticatedPrincipal principal = contextResolver.resolvePrincipal(httpRequest);

        List<PackageSummary> summaries = listPackagesQuery.execute(principal, offset, limit);

        List<PackageSummaryResponse> response = summaries.stream()
                .map(s -> new PackageSummaryResponse(
                        s.packageId().toString(),
                        s.packageType().name(),
                        s.status().name(),
                        s.documentCount(),
                        s.createdAt(),
                        s.updatedAt()
                ))
                .toList();
        return ResponseEntity.ok(response);
    }
}
