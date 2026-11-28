package com.securedoc.platform.application.pipeline;

import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.PipelineJobRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.ExportDestinations;
import com.securedoc.platform.domain.pipeline.PipelineJob;
import com.securedoc.platform.domain.pipeline.PipelineJobStatus;
import com.securedoc.platform.domain.pipeline.PipelineStage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Clock;
import java.util.Objects;
import java.util.UUID;

/**
 * Queues export jobs (task 0027). The master destination is the one the delivery port is set up with: LexaVault has
 * one master destination per tenant (the document store, or the local export folder, which has no connection and is given a
 * derived id, see {@link ExportDestinations}). Split documents are queued one job per derived delivery.
 */
public final class ExportJobQueue implements ExportJobScheduler, SplitExportScheduler {
    private static final Logger log = LoggerFactory.getLogger(ExportJobQueue.class);
    static final ActorId SYSTEM_ACTOR = ActorId.of(UUID.fromString("00000000-0000-0000-0000-000000000000"));

    private final PipelineJobRepository jobs;
    private final DeliveryPort deliveryPort;
    private final AuditService audit;
    private final ExportJobSettings settings;
    private final Clock clock;

    public ExportJobQueue(PipelineJobRepository jobs, DeliveryPort deliveryPort, AuditService audit,
                          ExportJobSettings settings, Clock clock) {
        this.jobs = Objects.requireNonNull(jobs);
        this.deliveryPort = Objects.requireNonNull(deliveryPort);
        this.audit = Objects.requireNonNull(audit);
        this.settings = Objects.requireNonNull(settings);
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public void queueCaseExport(TenantId tenantId, PackageId packageId, int exportCycle, CorrelationId correlationId) {
        IntegrationConnectionId destination = masterDestination(tenantId);
        queue(PipelineJob.ready(tenantId, PipelineStage.EXPORT, packageId, null, destination, null, exportCycle,
                clock.instant()), correlationId);
    }

    @Override
    public void queueSplitExport(TenantId tenantId, PackageId packageId, DocumentId sourceDocumentId,
                                 UUID derivedDeliveryId, IntegrationConnectionId destinationId, int exportCycle,
                                 CorrelationId correlationId) {
        queue(PipelineJob.ready(tenantId, PipelineStage.SPLIT_EXPORT, packageId, sourceDocumentId, destinationId,
                Objects.requireNonNull(derivedDeliveryId, "derivedDeliveryId"), exportCycle, clock.instant()),
                correlationId);
    }

    private void queue(PipelineJob job, CorrelationId correlationId) {
        if (!jobs.enqueue(job)) {
            log.info("EXPORT_JOB_ALREADY_QUEUED package={} stage={} cycle={}", job.packageId(), job.stage(),
                    job.exportCycle());
            return;
        }
        audit.recordSuccess(job.tenantId(), SYSTEM_ACTOR, AuditAction.EXPORT_JOB_QUEUED, "DocumentPackage",
                job.packageId().toString(), job.packageId(), correlationId,
                "Export job queued: job=" + job.id() + ", stage=" + job.stage() + ", cycle=" + job.exportCycle()
                        + ", destination=" + destinationText(job.destinationId()));
        log.info("EXPORT_JOB_QUEUED job={} package={} stage={} cycle={}", job.id(), job.packageId(), job.stage(),
                job.exportCycle());
    }

    static String destinationText(IntegrationConnectionId destination) {
        return destination == null ? "none" : destination.value().toString();
    }

    /**
     * The master destination: its connection id, or for the local export folder its derived id (task 0028, so it is
     * checked and paused like any destination). When the destination is not set up the job is still queued with none:
     * its run then fails with the set-up code (LV-DLV-006) and goes to the IT queue.
     */
    public IntegrationConnectionId masterDestination(TenantId tenantId) {
        try {
            IntegrationConnectionId id = deliveryPort.describe(tenantId).connectionId();
            return id == null ? ExportDestinations.localFolder(tenantId) : id;
        } catch (RuntimeException notSetUp) {
            log.warn("EXPORT_JOB_DESTINATION_NOT_RESOLVED tenant={} type={}", tenantId,
                    notSetUp.getClass().getSimpleName());
            return null;
        }
    }

}
