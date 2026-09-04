package com.securedoc.platform.infrastructure.persistence.repository;
import com.securedoc.platform.infrastructure.persistence.entity.DeliverySubmissionDocumentEntity;
import org.springframework.data.jpa.repository.JpaRepository;
import java.util.List; import java.util.UUID;
public interface SpringDataDeliverySubmissionDocumentRepository extends JpaRepository<DeliverySubmissionDocumentEntity,UUID>{
    List<DeliverySubmissionDocumentEntity> findBySubmissionIdOrderByFileNameAsc(UUID submissionId);
    void deleteBySubmissionId(UUID submissionId);
}
