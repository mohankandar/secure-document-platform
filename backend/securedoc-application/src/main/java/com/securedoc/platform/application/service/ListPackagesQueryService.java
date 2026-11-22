package com.securedoc.platform.application.service;

import com.securedoc.platform.application.business.model.BusinessIdentitySnapshot;
import com.securedoc.platform.application.business.service.BusinessIdentityService;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.intake.query.*;
import com.securedoc.platform.application.port.in.ListPackagesQuery;
import com.securedoc.platform.application.port.out.PackageRepository;
import com.securedoc.platform.application.port.out.ProcessingIncidentRepository;
import com.securedoc.platform.domain.document.DocumentPackage;
import com.securedoc.platform.domain.document.PackageStatus;
import com.securedoc.platform.domain.error.LvErrorCode;
import java.util.*;

public final class ListPackagesQueryService implements ListPackagesQuery {
    private final PackageRepository packageRepository; private final BusinessIdentityService businessIdentityService;
    private final ProcessingIncidentRepository incidents;
    public ListPackagesQueryService(PackageRepository packageRepository,BusinessIdentityService businessIdentityService){this(packageRepository,businessIdentityService,null);}
    /** Task 0038: with the incidents, a stopped case carries the LV code of why it stopped. */
    public ListPackagesQueryService(PackageRepository packageRepository,BusinessIdentityService businessIdentityService,
                                    ProcessingIncidentRepository incidents){this.packageRepository=packageRepository;this.businessIdentityService=businessIdentityService;this.incidents=incidents;}
    @Override public List<PackageSummary> execute(AuthenticatedPrincipal principal,int offset,int limit){
        if(offset<0)offset=0;if(limit<=0)limit=20;if(limit>100)limit=100;
        return packageRepository.findByTenantId(principal.tenantId(),offset,limit).stream().map(pkg->new PackageSummary(
                pkg.id(),pkg.packageType(),pkg.status(),identifiers(pkg,principal),pkg.documentCount(),pkg.createdAt(),pkg.updatedAt(),
                stopReasonCode(pkg,principal))).toList();
    }
    /**
     * Task 0038: the code of the case's latest processing problem, for a FAILED case only. Only a code LexaVault knows
     * (the incident's own LV code, else the one its reason maps to); never the summary, a detail or metadata.
     */
    private String stopReasonCode(DocumentPackage pkg,AuthenticatedPrincipal principal){
        if(incidents==null||pkg.status()!=PackageStatus.FAILED)return null;
        return incidents.findLatestByPackageIdAndTenantId(pkg.id(),principal.tenantId()).map(incident->{
            Map<String,String> meta=incident.safeMetadata()==null?Map.of():incident.safeMetadata();
            return LvErrorCode.fromCode(meta.get("lvCode"))
                    .or(()->com.securedoc.platform.application.error.LvErrorCodes.forDetail(incident.reasonCode()))
                    .map(LvErrorCode::code).orElse(null);
        }).orElse(null);
    }
    private List<BusinessIdentifierSummary> identifiers(DocumentPackage pkg,AuthenticatedPrincipal principal){
        return pkg.businessRecordId().map(id->businessIdentityService.get(id,principal.tenantId()).identifiers().stream()
                .map(i->new BusinessIdentifierSummary(i.key(),i.label(),i.value(),i.primary(),i.searchable(),i.displayable(),i.displayOrder())).toList()).orElse(List.of());
    }
}
