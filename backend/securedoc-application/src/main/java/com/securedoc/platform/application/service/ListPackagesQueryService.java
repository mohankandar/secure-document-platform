package com.securedoc.platform.application.service;

import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.query.PackageSummary;
import com.securedoc.platform.application.port.in.ListPackagesQuery;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.domain.document.DocumentPackage;

import java.util.List;
import java.util.Objects;

/**
 * Application service implementing the list-packages query.
 */
public final class ListPackagesQueryService implements ListPackagesQuery {

    private final PackageRepository packageRepository;

    public ListPackagesQueryService(PackageRepository packageRepository) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
    }

    @Override
    public List<PackageSummary> execute(AuthenticatedPrincipal principal, int offset, int limit) {
        Objects.requireNonNull(principal, "Principal must not be null");
        if (offset < 0) offset = 0;
        if (limit <= 0) limit = 20;
        if (limit > 100) limit = 100;

        List<DocumentPackage> packages = packageRepository
                .findByTenantId(principal.tenantId(), offset, limit);

        return packages.stream()
                .map(pkg -> PackageSummary.of(
                        pkg.id(),
                        pkg.packageType(),
                        pkg.status(),
                        pkg.documentCount(),
                        pkg.createdAt(),
                        pkg.updatedAt()
                ))
                .toList();
    }
}
