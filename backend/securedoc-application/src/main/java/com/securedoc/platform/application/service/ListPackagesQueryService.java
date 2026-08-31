package com.securedoc.platform.application.service;

import com.securedoc.platform.application.business.model.BusinessIdentitySnapshot;
import com.securedoc.platform.application.business.service.BusinessIdentityService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.query.*;
import com.securedoc.platform.application.port.in.ListPackagesQuery;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.domain.document.DocumentPackage;
import java.util.*;

public final class ListPackagesQueryService implements ListPackagesQuery {
    private final PackageRepository packageRepository; private final BusinessIdentityService businessIdentityService;
    public ListPackagesQueryService(PackageRepository packageRepository,BusinessIdentityService businessIdentityService){this.packageRepository=packageRepository;this.businessIdentityService=businessIdentityService;}
    @Override public List<PackageSummary> execute(AuthenticatedPrincipal principal,int offset,int limit){
        if(offset<0)offset=0;if(limit<=0)limit=20;if(limit>100)limit=100;
        return packageRepository.findByTenantId(principal.tenantId(),offset,limit).stream().map(pkg->new PackageSummary(
                pkg.id(),pkg.packageType(),pkg.status(),identifiers(pkg,principal),pkg.documentCount(),pkg.createdAt(),pkg.updatedAt())).toList();
    }
    private List<BusinessIdentifierSummary> identifiers(DocumentPackage pkg,AuthenticatedPrincipal principal){
        return pkg.businessRecordId().map(id->businessIdentityService.get(id,principal.tenantId()).identifiers().stream()
                .map(i->new BusinessIdentifierSummary(i.key(),i.label(),i.value(),i.primary(),i.searchable(),i.displayable(),i.displayOrder())).toList()).orElse(List.of());
    }
}
