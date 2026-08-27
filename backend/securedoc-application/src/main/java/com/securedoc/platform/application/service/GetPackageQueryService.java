package com.securedoc.platform.application.service;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.intake.query.PackageDetail;
import com.securedoc.platform.application.port.in.GetPackageQuery;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.domain.document.Document;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.identity.PackageId;

import java.util.List;
import java.util.Objects;

/**
 * Application service implementing the get-package-detail query.
 */
public final class GetPackageQueryService implements GetPackageQuery {

    private final PackageRepository packageRepository;

    public GetPackageQueryService(PackageRepository packageRepository) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
    }

    @Override
    public PackageDetail execute(PackageId packageId, AuthenticatedPrincipal principal) {
        Objects.requireNonNull(packageId, "PackageId must not be null");
        Objects.requireNonNull(principal, "Principal must not be null");

        DocumentPackage pkg = packageRepository
                .findByIdAndTenantId(packageId, principal.tenantId())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));

        List<PackageDetail.DocumentSummary> docSummaries = pkg.documents().stream()
                .map(this::toDocumentSummary)
                .toList();

        return new PackageDetail(
                pkg.id(),
                pkg.packageType(),
                pkg.status(),
                pkg.metadata().asMap(),
                docSummaries,
                pkg.createdAt(),
                pkg.updatedAt()
        );
    }

    private PackageDetail.DocumentSummary toDocumentSummary(Document doc) {
        return new PackageDetail.DocumentSummary(
                doc.id(),
                doc.fileName().value(),
                doc.mimeType().value(),
                doc.sizeBytes(),
                doc.documentType(),
                doc.status()
        );
    }
}
