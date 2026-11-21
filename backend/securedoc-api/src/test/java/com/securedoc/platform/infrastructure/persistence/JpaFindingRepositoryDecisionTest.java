package com.securedoc.platform.infrastructure.persistence;

import com.securedoc.platform.domain.finding.*;
import com.securedoc.platform.domain.identity.*;
import com.securedoc.platform.domain.vo.Confidence;
import com.securedoc.platform.infrastructure.persistence.adapter.JpaFindingRepositoryAdapter;
import com.securedoc.platform.infrastructure.persistence.entity.FindingEntity;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataFindingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.orm.ObjectOptimisticLockingFailureException;

import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

/** Task 0023 (D5, #160): a review decision is saved only while the stored finding is still pending. */
class JpaFindingRepositoryDecisionTest {
    private SpringDataFindingRepository jpa;
    private JpaFindingRepositoryAdapter adapter;
    private Finding decided;
    private FindingEntity stored;

    @BeforeEach
    void setUp() {
        jpa = mock(SpringDataFindingRepository.class);
        adapter = new JpaFindingRepositoryAdapter(jpa);
        decided = Finding.create(TenantId.generate(), DocumentId.generate(), EntityType.SSN,
                FindingLocation.withBounds(1, 0, 9, FindingLocation.BoundingBox.of(0.1, 0.2, 0.3, 0.04)),
                Confidence.of(0.62), DetectorSource.of("test"));
        decided.dismiss(ActorId.generate(), null);
        stored = new FindingEntity();
        stored.setId(decided.id().value());
        stored.setTenantId(decided.tenantId().value());
        stored.setStatus(FindingStatus.DETECTED.name());
        stored.setDetectedAt(Instant.now());
        stored.setVersion(3);
        when(jpa.findByIdAndTenantId(decided.id().value(), decided.tenantId().value())).thenReturn(Optional.of(stored));
    }

    @Test
    void aPendingFindingIsSaved() {
        assertTrue(adapter.saveDecisionIfPending(decided));
        verify(jpa).save(stored);
        assertEquals(FindingStatus.DISMISSED.name(), stored.getStatus());
    }

    @Test
    void aFindingSomeoneElseDecidedIsNotOverwritten() {
        stored.setStatus(FindingStatus.CONFIRMED.name());

        assertFalse(adapter.saveDecisionIfPending(decided));
        verify(jpa, never()).save(any());
    }

    @Test
    void twoSavesThatMeetInTheDatabaseAnswerFalseInsteadOfA500() {
        when(jpa.save(any())).thenThrow(new ObjectOptimisticLockingFailureException(FindingEntity.class, decided.id().value()));

        assertFalse(adapter.saveDecisionIfPending(decided));
    }
}
