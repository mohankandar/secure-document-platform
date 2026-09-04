package com.securedoc.platform.infrastructure.persistence.repository;
import com.securedoc.platform.infrastructure.persistence.entity.DeliveryAttemptEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.UUID;
public interface SpringDataDeliveryAttemptRepository extends JpaRepository<DeliveryAttemptEntity,UUID>{}
