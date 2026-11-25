package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.ConditionKey;
import com.documentplatform.notifications.ConditionState;
import com.documentplatform.notifications.ConditionStore;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.RecordIdentifier;
import com.securedoc.platform.application.pipeline.ExportJobService;
import com.securedoc.platform.application.port.out.ExportDestinationStateRepository;
import com.securedoc.platform.application.port.out.ExportReachabilityAlertRepository;
import com.securedoc.platform.application.port.out.ProcessingStorageConfigurationRepository;
import com.securedoc.platform.application.storage.ProcessingStorageConfigurationService;
import com.securedoc.platform.application.port.out.WorkGate;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.pipeline.ExportDestinationStatus;
import com.securedoc.platform.domain.storage.ProcessingStorageValidationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Task 0059: turns the IT notices LexaVault already raises (as audit records, alerts and Support groups) into notifications.
 * Each is read from its stored state, so nothing in processing changes and two servers see the same; the platform keeps each as
 * one condition (one alert, repeats, one Recovered). Only for entities whose e-mail is on. A message names the destination by
 * its internal id.
 *
 * <p>"Busy" is a destination that keeps asking to slow down: from its first answer to {@link #BUSY_QUIET} after the last wait
 * ended. The message goes out when that has lasted 30 minutes.
 */
@NotificationsOn
@Component
public class OperationalNotices {

    static final Duration BUSY_QUIET = Duration.ofMinutes(10);
    private static final Duration STORAGE_FRESH = Duration.ofSeconds(60);
    private static final AppLink EXPORT_SCREEN = AppLink.of("/support/export");
    private static final AppLink STATUS_SCREEN = AppLink.of("/support/status");
    private static final Logger log = LoggerFactory.getLogger(OperationalNotices.class);

    /** The stored states read, per entity. */
    public record Sources(Function<TenantId, List<ExportDestinationStatus>> destinations, Predicate<TenantId> unreachable,
                          Function<TenantId, List<ExportJobService.ItGroup>> needsIt, Predicate<TenantId> storageStopped) {
    }

    private final Notifier notifier;
    private final ConditionStore conditions;
    private final MailSettingsStore mail;
    private final boolean appAddressSet;
    private final Sources sources;
    private final StoredNotices stored;
    private final Clock clock;

    private WorkGate workGate = WorkGate.open();

    @org.springframework.beans.factory.annotation.Autowired
    void workGate(WorkGate gate) {
        this.workGate = gate;
    }

    @org.springframework.beans.factory.annotation.Autowired
    public OperationalNotices(Notifier notifier, ConditionStore conditions, MailSettingsStore mail,
                              NotificationsProperties properties, ExportDestinationStateRepository destinations, ExportReachabilityAlertRepository alerts,
                              ExportJobService exports, StoredNotices stored, ProcessingStorageConfigurationService storage,
                              ProcessingStorageConfigurationRepository storageRepository) {
        this(notifier, conditions, mail, properties.appAddress() != null, new Sources(destinations::findByTenantId,
                entity -> alerts.find(entity).map(ExportReachabilityAlertRepository.Alert::raised).orElse(false),
                exports::needsItGroups, entity -> storageStopped(entity, storage, storageRepository)), stored, Clock.systemUTC());
    }

    OperationalNotices(Notifier notifier, ConditionStore conditions, MailSettingsStore mail, boolean appAddressSet,
                       Sources sources, StoredNotices stored, Clock clock) {
        this.stored = stored;
        this.appAddressSet = appAddressSet;
        this.notifier = notifier;
        this.conditions = conditions;
        this.mail = mail;
        this.sources = sources;
        this.clock = clock;
    }

    /**
     * Whether processing of the entity's work has stopped because its storage can't be used: no server that is up finds it
     * healthy (the same checks processing itself waits for). Storage that was never set up is not a stop.
     */
    static boolean storageStopped(TenantId entity, ProcessingStorageConfigurationService storage,
                                  ProcessingStorageConfigurationRepository repository) {
        return storage.effectiveProvider(entity).map(provider -> {
            var checks = repository.findActiveNodeValidations(provider.id(), Instant.now().minus(STORAGE_FRESH));
            return !checks.isEmpty() && checks.stream().noneMatch(v -> v.status() == ProcessingStorageValidationStatus.HEALTHY);
        }).orElse(false);
    }

    @Scheduled(fixedDelayString = "${securedoc.notifications.check-interval-ms:60000}", initialDelayString = "${securedoc.notifications.check-interval-ms:60000}")
    public void check() {
        List<TenantId> connected;
        List<ConditionState> active;
        try {
            connected = appAddressSet ? mail.connected() : List.of();
            active = conditions.active();
        } catch (RuntimeException e) {
            log.warn("NOTIFICATION_CHECK_FAILED type={}", e.getClass().getSimpleName());
            return;
        }
        for (TenantId entity : connected) {
            try {
                check(entity, active);
            } catch (RuntimeException e) {
                log.warn("NOTIFICATION_CHECK_FAILED entity={} type={}", entity, e.getClass().getSimpleName());
            }
        }
        // Switched off (or the entity suspended) meanwhile: ended without a message.
        Set<String> on = new HashSet<>();
        connected.forEach(e -> on.add(EntityNotifications.tenant(e).value()));
        active.stream().map(ConditionState::key)
                .filter(k -> (LexaVaultEvents.OPERATIONAL.contains(k.type()) || LexaVaultEvents.WORK.contains(k.type())) && !on.contains(k.tenant().value()))
                .forEach(notifier::conditionSuperseded);
    }

    private void check(TenantId entity, List<ConditionState> active) {
        if (!workGate.mayTakeWork()) {
            // Task 0060: after a restore the stored states are from the backup. Only this server's own checks are told.
            stored.ownChecks(entity).forEach(notifier::conditionFailing);
            return;
        }
        List<NotificationEvent> failing = new ArrayList<>();
        BiFunction<EventTypeId, AppLink, NotificationEvent.Builder> event = (type, link) -> NotificationEvent
                .builder(type, EntityNotifications.tenant(entity)).entity(EntityNotifications.entity(entity)).link(link);
        Instant now = clock.instant();
        for (ExportDestinationStatus d : sources.destinations().apply(entity)) {
            RecordIdentifier id = RecordIdentifier.of(d.connectionId().value().toString());
            switch (d.state()) {
                case PAUSED -> failing.add(event.apply(LexaVaultEvents.DESTINATION_PAUSED, EXPORT_SCREEN).identifier(id).build());
                case NEEDS_IT -> failing.add(event.apply(LexaVaultEvents.DESTINATION_NEEDS_IT, EXPORT_SCREEN).identifier(id).build());
                case WORKING -> { }
            }
            if (d.rateLimitedUntil() != null && now.isBefore(d.rateLimitedUntil().plus(BUSY_QUIET))) {
                failing.add(event.apply(LexaVaultEvents.DESTINATION_BUSY, EXPORT_SCREEN).identifier(id).build());
            }
        }
        if (sources.unreachable().test(entity)) {
            failing.add(event.apply(LexaVaultEvents.DESTINATIONS_UNREACHABLE, EXPORT_SCREEN).build());
        }
        List<ExportJobService.ItGroup> groups = sources.needsIt().apply(entity);
        if (!groups.isEmpty()) {
            failing.add(event.apply(LexaVaultEvents.EXPORTS_NEED_IT, EXPORT_SCREEN).count("problems", groups.size())
                    .count("cases", groups.stream().flatMap(g -> g.caseIds().stream()).distinct().count()).build());
        }
        if (sources.storageStopped().test(entity)) {
            failing.add(event.apply(LexaVaultEvents.STORAGE_STOPPED, STATUS_SCREEN).build());
        }
        failing.addAll(stored.goingOn(entity));
        Set<ConditionKey> reported = new HashSet<>();
        for (NotificationEvent e : failing) {
            notifier.conditionFailing(e);
            reported.add(ConditionKey.of(e));
        }
        String tenant = EntityNotifications.tenant(entity).value();
        active.stream().map(ConditionState::key)
                .filter(k -> k.tenant().value().equals(tenant) && !reported.contains(k) && stored.mine(k.type(), k.identifier()))
                .forEach(k -> {
                    if (LexaVaultEvents.OPERATIONAL.contains(k.type())) notifier.conditionCleared(k);
                    else if (LexaVaultEvents.WORK.contains(k.type())) notifier.conditionSuperseded(k);
                });
        stored.publishNew(entity, notifier);
    }
}
