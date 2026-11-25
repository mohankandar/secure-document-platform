package com.securedoc.platform.api.notifications;

import com.documentplatform.monitoring.CheckResult;
import com.documentplatform.monitoring.CheckState;
import com.documentplatform.monitoring.DependencyCheck;
import com.documentplatform.monitoring.DependencyStatus;
import com.documentplatform.monitoring.MonitoringCode;
import com.documentplatform.monitoring.Verdict;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import org.springframework.transaction.PlatformTransactionManager;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0060: the key service and the erase log among this server's own checks; each server tells and clears its own. */
class OwnCheckNoticesTest {
    private static List<String> told(String check, CheckState state) {
        DependencyCheck named = mock(DependencyCheck.class);
        when(named.name()).thenReturn(check);
        when(named.group()).thenReturn("services");
        CheckResult result = CheckResult.of(named, state == CheckState.WORKING ? Verdict.working()
                : state == CheckState.DOWN ? Verdict.down(MonitoringCode.MON_KEY_SERVICE_DOWN, Map.of("keyService", "test"))
                : Verdict.problems(MonitoringCode.MON_KEY_SERVICE_DOWN, Map.of("keyService", "test")),
                java.time.Instant.now(), java.time.Duration.ZERO);
        DependencyStatus status = mock(DependencyStatus.class);
        when(status.results()).thenReturn(List.of(result));
        return notices(status).ownChecks(TenantId.generate()).stream()
                .map(e -> e.type().value() + "|" + e.identifier().value()).toList();
    }

    private static StoredNotices notices(DependencyStatus status) {
        var beans = new StaticListableBeanFactory(Map.of("status", status));
        return new StoredNotices(mock(org.springframework.jdbc.core.JdbcTemplate.class), null,
                beans.getBeanProvider(DependencyStatus.class),
                beans.getBeanProvider(com.securedoc.platform.application.intake.IntakeGate.class),
                mock(PlatformTransactionManager.class), "server-1");
    }

    @Test
    void aCheckThatIsNotWorkingIsToldByItsServer() {
        for (CheckState bad : List.of(CheckState.DOWN, CheckState.HAVING_PROBLEMS)) {
            assertEquals(List.of("system.key-service-down|server-1"), told("key-service", bad));
            assertEquals(List.of("system.erase-log-stop|server-1"), told("erase-log", bad));
        }
        assertEquals(List.of(), told("key-service", CheckState.WORKING));
        // The check's real name, so a model server that is down is told (it never was before this task).
        assertEquals(List.of("system.ai-model-down|server-1"), told("service-model-server", CheckState.DOWN));
        StoredNotices notices = notices(mock(DependencyStatus.class));
        for (var type : List.of(LexaVaultEvents.KEY_SERVICE_DOWN, LexaVaultEvents.ERASE_LOG_STOP)) {
            assertTrue(notices.mine(type, com.documentplatform.notifications.RecordIdentifier.of("server-1")));
            assertFalse(notices.mine(type, com.documentplatform.notifications.RecordIdentifier.of("server-2")));
            assertTrue(LexaVaultEvents.OPERATIONAL.contains(type));
        }
    }
}
