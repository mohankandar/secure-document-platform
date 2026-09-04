package com.securedoc.platform.infrastructure.persistence.repository;
import com.securedoc.platform.infrastructure.persistence.entity.DerivedDeliveryAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface SpringDataDerivedDeliveryAttemptRepository extends JpaRepository<DerivedDeliveryAttemptEntity,UUID>{}
