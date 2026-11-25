package com.securedoc.platform.application.port.out;

import com.securedoc.platform.application.pipeline.QueueView;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.PipelineStage;

import java.time.Duration;
import java.util.Map;

/** Task 0078: the work of each stage, for one entity or (null) all of them. */
public interface QueueWorkPort {
    Map<PipelineStage, QueueView.Work> work(TenantId tenantId, Duration window);
}
