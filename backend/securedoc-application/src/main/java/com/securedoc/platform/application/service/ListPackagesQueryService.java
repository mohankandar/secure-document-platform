package com.securedoc.platform.application.service;

import com.securedoc.platform.application.casefields.CaseFieldSnapshot;
import com.securedoc.platform.application.casefields.CaseFieldService;
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
    private final PackageRepository packageRepository; private final CaseFieldService caseFields;
    private final ProcessingIncidentRepository incidents;
    public ListPackagesQueryService(PackageRepository packageRepository,CaseFieldService caseFields){this(packageRepository,caseFields,null);}
    /** Task 0038: with the incidents, a stopped case carries the LV code of why it stopped. */
    public ListPackagesQueryService(PackageRepository packageRepository,CaseFieldService caseFields,
                                    ProcessingIncidentRepository incidents){this.packageRepository=packageRepository;this.caseFields=caseFields;this.incidents=incidents;}
    private com.securedoc.platform.application.port.out.PackageRejectionRepository rejections;
    /** Task 0071: with the reasons of rejected cases. */
    public ListPackagesQueryService withRejections(com.securedoc.platform.application.port.out.PackageRejectionRepository value){rejections=Objects.requireNonNull(value);return this;}
    @Override public List<PackageSummary> execute(AuthenticatedPrincipal principal,int offset,int limit){
        if(offset<0)offset=0;if(limit<=0)limit=20;if(limit>100)limit=100;
        List<com.documentplatform.fields.FieldDefinition> fields=caseFields.active(principal.tenantId());
        return packageRepository.findByTenantId(principal.tenantId(),offset,limit).stream().map(pkg->new PackageSummary(
                pkg.id(),pkg.packageType(),pkg.status(),identifiers(pkg,principal,fields),pkg.documentCount(),pkg.createdAt(),pkg.updatedAt(),
                stopReasonCode(pkg,principal))).toList();
    }
    /**
     * Task 0038: the code of the case's latest processing problem, for a FAILED case; a REJECTED one has its reason. Only a code LexaVault knows
     * (the incident's own LV code, else the one its reason maps to); never the summary, a detail or metadata.
     */
    private String stopReasonCode(DocumentPackage pkg,AuthenticatedPrincipal principal){
        // Task 0071: a rejected case has the reason the person chose.
        if(pkg.status()==PackageStatus.REJECTED)return rejections==null?null
                :rejections.find(principal.tenantId(),pkg.id()).map(reason->reason.code().code()).orElse(null);
        if(incidents==null||pkg.status()!=PackageStatus.FAILED)return null;
        return incidents.findLatestByPackageIdAndTenantId(pkg.id(),principal.tenantId()).map(incident->{
            Map<String,String> meta=incident.safeMetadata()==null?Map.of():incident.safeMetadata();
            return LvErrorCode.fromCode(meta.get("lvCode"))
                    .or(()->com.securedoc.platform.application.error.LvErrorCodes.forDetail(incident.reasonCode()))
                    .map(LvErrorCode::code).orElse(null);
        }).orElse(null);
    }
    private List<BusinessIdentifierSummary> identifiers(DocumentPackage pkg,AuthenticatedPrincipal principal,
                                                        List<com.documentplatform.fields.FieldDefinition> fields){
        // Never the value of a sensitive field in a list.
        return pkg.businessRecordId().map(id->caseFields.get(id,principal.tenantId(),fields).values().stream()
                .map(BusinessIdentifierSummary::of).toList()).orElse(List.of());
    }
}
