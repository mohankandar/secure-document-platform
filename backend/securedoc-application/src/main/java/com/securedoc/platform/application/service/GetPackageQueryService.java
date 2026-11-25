package com.securedoc.platform.application.service;

import com.securedoc.platform.application.casefields.CaseFieldSnapshot;
import com.securedoc.platform.application.casefields.CaseFieldService;
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
    private final CaseFieldService caseFields;
    public GetPackageQueryService(PackageRepository packageRepository, CaseFieldService caseFields) {
        this.packageRepository=Objects.requireNonNull(packageRepository); this.caseFields = Objects.requireNonNull(caseFields);
    }
    private java.util.function.Predicate<AuthenticatedPrincipal> seesSensitive = principal -> false;
    /** Task 0061: who sees the values of sensitive case fields. Nobody unless this says so. */
    public GetPackageQueryService withSensitiveFor(java.util.function.Predicate<AuthenticatedPrincipal> allowed) {
        this.seesSensitive = Objects.requireNonNull(allowed);
        return this;
    }
    @Override public PackageDetail execute(PackageId packageId, AuthenticatedPrincipal principal) {
        DocumentPackage pkg=packageRepository.findByIdAndTenantId(packageId,principal.tenantId())
                .orElseThrow(()->new NotFoundException("DocumentPackage",packageId.toString()));
        List<BusinessIdentifierSummary> identifiers=pkg.businessRecordId().map(id->toSummaries(seesSensitive.test(principal)
                ? caseFields.getWithSensitive(id,principal.tenantId()) : caseFields.get(id,principal.tenantId()))).orElse(List.of());
        List<PackageDetail.DocumentSummary> docs=pkg.documents().stream().map(d->new PackageDetail.DocumentSummary(
                d.id(),d.fileName().value(),d.mimeType().value(),d.sizeBytes(),d.documentType(),d.status(),d.sensitivityLevel())).toList();
        return new PackageDetail(pkg.id(),pkg.packageType(),pkg.status(),identifiers,pkg.metadata().asMap(),docs,pkg.createdAt(),pkg.updatedAt(),
                pkg.sensitivityLevel(),pkg.effectiveSensitivityLevel());
    }
    private List<BusinessIdentifierSummary> toSummaries(CaseFieldSnapshot s){return s.values().stream().map(BusinessIdentifierSummary::of).toList();}
}
