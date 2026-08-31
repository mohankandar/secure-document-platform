package com.securedoc.platform.application.service;

import com.securedoc.platform.application.business.model.BusinessIdentitySnapshot;
import com.securedoc.platform.application.business.service.BusinessIdentityService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.intake.query.*;
import com.securedoc.platform.application.port.in.GetPackageQuery;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.domain.document.*;
import com.securedoc.platform.domain.identity.PackageId;
import java.util.*;

public final class GetPackageQueryService implements GetPackageQuery {
    private final PackageRepository packageRepository;
    private final BusinessIdentityService businessIdentityService;
    public GetPackageQueryService(PackageRepository packageRepository, BusinessIdentityService businessIdentityService) {
        this.packageRepository=Objects.requireNonNull(packageRepository); this.businessIdentityService=Objects.requireNonNull(businessIdentityService);
    }
    @Override public PackageDetail execute(PackageId packageId, AuthenticatedPrincipal principal) {
        DocumentPackage pkg=packageRepository.findByIdAndTenantId(packageId,principal.tenantId())
                .orElseThrow(()->new NotFoundException("DocumentPackage",packageId.toString()));
        List<BusinessIdentifierSummary> identifiers=pkg.businessRecordId().map(id->toSummaries(businessIdentityService.get(id,principal.tenantId()))).orElse(List.of());
        List<PackageDetail.DocumentSummary> docs=pkg.documents().stream().map(d->new PackageDetail.DocumentSummary(
                d.id(),d.fileName().value(),d.mimeType().value(),d.sizeBytes(),d.documentType(),d.status())).toList();
        return new PackageDetail(pkg.id(),pkg.packageType(),pkg.status(),identifiers,pkg.metadata().asMap(),docs,pkg.createdAt(),pkg.updatedAt());
    }
    private List<BusinessIdentifierSummary> toSummaries(BusinessIdentitySnapshot s){return s.identifiers().stream().map(i->new BusinessIdentifierSummary(
            i.key(),i.label(),i.value(),i.primary(),i.searchable(),i.displayable(),i.displayOrder())).toList();}
}
