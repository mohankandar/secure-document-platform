package com.securedoc.platform.api.platform;

import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.processing.ProcessingCapacity;
import com.securedoc.platform.api.security.ProductAuthorizationPolicy;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.pipeline.QueueView;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Task 0078: an IT Admin gets their own entity's numbers only; Platform Operations the sum. */
class PipelineQueueControllerTest {
    private final TenantId tenant = TenantId.generate();
    private final List<TenantId> asked = new ArrayList<>();
    private final ProcessingCapacity capacity = mock(ProcessingCapacity.class);
    private final RequestContextResolver resolver = mock(RequestContextResolver.class);
    private final PipelineQueueController controller = new PipelineQueueController((t, w) -> {
        asked.add(t);
        return Map.of(PipelineStage.FIND, new QueueView.Work(6, 2, 1, 75L, 2, 10, 20));
    }, capacity, new ProductAuthorizationPolicy(), resolver);

    @Test
    void theEntityOfTheSignedInItAdminOnly() {
        signedIn("TENANT_IT_ADMIN", true);
        when(capacity.stages()).thenReturn(List.of(new ProcessingCapacity.StageState(PipelineStage.FIND, 3, 2, 1, 5, 5, null, 6)));
        var queue = controller.queue(new MockHttpServletRequest());
        assertEquals(List.of(tenant), asked);
        assertFalse(queue.allEntities());
        assertEquals(20L, queue.etaSeconds());
        assertEquals(new PipelineQueueController.Stage("FIND", 6, 2, 1, 2, 3, 5, 75L, 30.0, 20L), queue.stages().get(1));
    }

    @Test
    void everyEntityAddedUpForPlatformOperations() {
        signedIn("PLATFORM_OPERATIONS_ADMIN", true);
        assertTrue(controller.queue(new MockHttpServletRequest()).allEntities());
        assertEquals(1, asked.size());
        assertEquals(null, asked.get(0));
    }

    @Test
    void nothingIsCountedWhileDocumentsAreNotProcessedInSteps() {
        signedIn("TENANT_IT_ADMIN", false);
        assertFalse(controller.queue(new MockHttpServletRequest()).staged());
        assertTrue(asked.isEmpty());
    }

    private void signedIn(String role, boolean staged) {
        when(capacity.staged()).thenReturn(staged);
        when(resolver.resolvePrincipal(any())).thenReturn(AuthenticatedPrincipal.of(ActorId.generate(), tenant, "it", Set.of(role)));
    }
}
