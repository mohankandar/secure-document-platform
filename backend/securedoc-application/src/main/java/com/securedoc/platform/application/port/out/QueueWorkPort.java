package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.pipeline.QueueView;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineStage;

import java.time.Duration;
import java.util.Map;
import java.util.Set;

/** Task 0078: the work of each stage, for one entity or (null) all of them; openBulkSources as the claim is asked with. */
public interface QueueWorkPort {
    Map<PipelineStage, QueueView.Work> work(TenantId tenantId, Duration window, Set<String> openBulkSources);
}
