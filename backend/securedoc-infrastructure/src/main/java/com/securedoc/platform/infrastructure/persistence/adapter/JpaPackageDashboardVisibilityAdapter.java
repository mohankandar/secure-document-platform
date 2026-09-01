package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.application.exception.NotFoundException;
import com.securedoc.platform.application.port.out.PackageDashboardVisibilityPort;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.entity.DocumentPackageEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataDocumentPackageRepository;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Objects;

@Component
public class JpaPackageDashboardVisibilityAdapter implements PackageDashboardVisibilityPort {

    private final SpringDataDocumentPackageRepository packageRepository;

    public JpaPackageDashboardVisibilityAdapter(SpringDataDocumentPackageRepository packageRepository) {
        this.packageRepository = Objects.requireNonNull(packageRepository);
    }

    @Override
    @Transactional
    public void hide(PackageId packageId, TenantId tenantId, ActorId actorId, Instant hiddenAt) {
        DocumentPackageEntity entity = packageRepository.findByIdAndTenantId(packageId.value(), tenantId.value())
                .orElseThrow(() -> new NotFoundException("DocumentPackage", packageId.toString()));
        if (entity.getDashboardHiddenAt() == null) {
            entity.setDashboardHiddenAt(hiddenAt);
            entity.setDashboardHiddenBy(actorId.value());
            packageRepository.save(entity);
        }
    }
}
