package com.securedoc.platform.api.notifications;

import com.documentplatform.licensing.spring.notifications.LicensingEvents;
import com.documentplatform.notifications.ConditionTiming;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.EventKind;
import com.documentplatform.notifications.EventType;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.RoleId;
import com.documentplatform.notifications.Schedule;
import com.documentplatform.notifications.approval.ApprovalType;

import java.time.Duration;
import java.util.List;
import java.util.stream.Stream;

/**
 * Task 0059: LexaVault's event types. A message carries the event's name, an id LexaVault made (never a case number or a name
 * from a document), counts, times and a link into the app.
 *
 * <p>In LexaVault every entity is a notification tenant of its own ({@link EntityNotifications}): settings, people, messages
 * and links of one entity never meet another's.
 */
public final class LexaVaultEvents {

    public static final RoleId IT_ADMIN = RoleId.of("TENANT_IT_ADMIN");
    public static final RoleId BUSINESS_ADMIN = RoleId.of("BUSINESS_ADMIN");
    public static final RoleId SUPERVISOR = RoleId.of("DOCUMENT_SUPERVISOR");

    public static final EventTypeId DESTINATION_PAUSED = EventTypeId.of("export.destination-paused");
    public static final EventTypeId DESTINATION_NEEDS_IT = EventTypeId.of("export.destination-needs-it");
    public static final EventTypeId DESTINATION_BUSY = EventTypeId.of("export.destination-busy");
    public static final EventTypeId DESTINATIONS_UNREACHABLE = EventTypeId.of("export.destinations-unreachable");
    public static final EventTypeId EXPORTS_NEED_IT = EventTypeId.of("export.cases-need-it");
    public static final EventTypeId STORAGE_STOPPED = EventTypeId.of("storage.processing-stopped");
    public static final EventTypeId INTAKE_STOPPED = EventTypeId.of("storage.intake-stopped");
    public static final EventTypeId TEST_MESSAGE = EventTypeId.of("notifications.test-message");
    public static final EventTypeId DELIVERY_APPROVAL_WAITING = EventTypeId.of("approval.delivery-waiting");
    public static final EventTypeId DELIVERY_APPROVED = EventTypeId.of("approval.delivery-approved");
    public static final EventTypeId DELIVERY_DECLINED = EventTypeId.of("approval.delivery-declined");

    public static final RoleId PROCESSOR = RoleId.of("DOCUMENT_PROCESSOR");
    public static final EventTypeId REVIEW_WAITING = EventTypeId.of("work.review-waiting");
    public static final EventTypeId REVIEW_PAST_TARGET = EventTypeId.of("work.review-past-target");
    public static final EventTypeId APPROVALS_WAITING = EventTypeId.of("work.approvals-waiting");
    public static final EventTypeId SOURCE_UNREACHABLE = EventTypeId.of("intake.source-unreachable");
    public static final EventTypeId FILES_REJECTED = EventTypeId.of("intake.files-rejected");
    public static final EventTypeId REPEATED_FAILURES = EventTypeId.of("processing.repeated-failures");
    public static final EventTypeId OVER_TARGET = EventTypeId.of("processing.over-target");
    public static final EventTypeId AI_MODEL_DOWN = EventTypeId.of("system.ai-model-down");
    public static final EventTypeId DISK_ALMOST_FULL = EventTypeId.of("system.disk-almost-full");
    /** Task 0060. */
    public static final EventTypeId KEY_SERVICE_DOWN = EventTypeId.of("system.key-service-down");
    public static final EventTypeId ERASE_LOG_STOP = EventTypeId.of("system.erase-log-stop");
    public static final EventTypeId POLICY_CHANGED = EventTypeId.of("security.redaction-policy-changed");
    public static final String FAILURES_IN_AN_HOUR = "failuresInAnHour";

    /** Counts of waiting work: repeated while there is some, ended without a word when there is none. */
    public static final List<EventTypeId> WORK = List.of(REVIEW_WAITING, REVIEW_PAST_TARGET, APPROVALS_WAITING);

    /** What {@link OperationalNotices} raises and clears, each a condition with one Recovered message. */
    public static final List<EventTypeId> OPERATIONAL = List.of(DESTINATION_PAUSED, DESTINATION_NEEDS_IT, DESTINATION_BUSY,
            DESTINATIONS_UNREACHABLE, EXPORTS_NEED_IT, STORAGE_STOPPED, SOURCE_UNREACHABLE, REPEATED_FAILURES, OVER_TARGET,
            AI_MODEL_DOWN, DISK_ALMOST_FULL, INTAKE_STOPPED, KEY_SERVICE_DOWN, ERASE_LOG_STOP);

    /** The technical messages an IT Admin sets per entity. */
    public static final List<EventTypeId> TECHNICAL = Stream.concat(OPERATIONAL.stream(), Stream.of(FILES_REJECTED)).toList();

    /** Approve delivery, by the Supervisor, as on the case. Links work for 24 hours. */
    public static final ApprovalType DELIVERY_APPROVAL = ApprovalType.of("deliver-case", DELIVERY_APPROVAL_WAITING,
            DELIVERY_APPROVED, DELIVERY_DECLINED, SUPERVISOR);

    /** The agreed default: told when it has lasted 15 minutes, again every 4 hours. */
    private static final ConditionTiming LASTING = ConditionTiming.DEFAULT;
    private static final ConditionTiming EVERY_4_HOURS = new ConditionTiming(Duration.ZERO, Duration.ofHours(4));
    private static final ConditionTiming AFTER_30_MINUTES =
            new ConditionTiming(Duration.ofMinutes(30), Duration.ofHours(4));

    private LexaVaultEvents() {
    }

    public static EventCatalogue catalogue() {
        EventCatalogue.Builder c = EventCatalogue.builder(IT_ADMIN);
        LicensingEvents.register(c, IT_ADMIN, BUSINESS_ADMIN);
        c.register(technical(DESTINATION_PAUSED, "Destination paused").critical().continuous(LASTING).build());
        c.register(technical(DESTINATION_NEEDS_IT, "Destination needs IT").critical().continuous(LASTING).build());
        c.register(technical(DESTINATIONS_UNREACHABLE, "Destinations can not be reached").critical().continuous(LASTING)
                .build());
        c.register(technical(EXPORTS_NEED_IT, "Cases waiting for IT").critical().continuous(LASTING)
                .count("problems", "Problems").count("cases", "Cases").build());
        c.register(technical(DESTINATION_BUSY, "Destination busy for more than 30 minutes")
                .schedule(Schedule.immediately()).continuous(AFTER_30_MINUTES).build());
        c.register(technical(STORAGE_STOPPED, "Processing stopped, storage not usable").critical().continuous(LASTING)
                .build());
        c.register(technical(INTAKE_STOPPED, "Intake stopped, too little free space").critical().continuous(LASTING)
                .build());
        c.register(technical(SOURCE_UNREACHABLE, "Intake source can not be reached").schedule(Schedule.immediately())
                .continuous(LASTING).build());
        c.register(technical(FILES_REJECTED, "File rejected at intake").schedule(Schedule.every4Hours()).build());
        c.register(technical(REPEATED_FAILURES, "Repeated processing failures").schedule(Schedule.immediately())
                .continuous(LASTING).count("cases", "Problems in the last hour")
                .threshold(FAILURES_IN_AN_HOUR, "Problems in an hour", 3, 1, 1000).build());
        c.register(technical(OVER_TARGET, "Cases over the time target").schedule(Schedule.every4Hours()).continuous(LASTING)
                .count("cases", "Cases").build());
        c.register(technical(AI_MODEL_DOWN, "AI model down").critical().continuous(LASTING).build());
        c.register(technical(KEY_SERVICE_DOWN, "Key service down or refusing").critical().continuous(LASTING).build());
        c.register(technical(ERASE_LOG_STOP, "Erase log not usable, or a restore waiting for its replay").critical()
                .continuous(LASTING).build());
        c.register(technical(DISK_ALMOST_FULL, "Disk almost full").critical().continuous(LASTING).build());
        c.register(work(REVIEW_WAITING, "Cases waiting for review", PROCESSOR, SUPERVISOR));
        c.register(work(REVIEW_PAST_TARGET, "Cases waiting past the review target", PROCESSOR, SUPERVISOR));
        c.register(work(APPROVALS_WAITING, "Cases waiting for approval", SUPERVISOR));
        c.register(EventType.builder(POLICY_CHANGED.value(), "Redaction policy changed", EventKind.BUSINESS)
                .schedule(Schedule.immediately()).recipients(BUSINESS_ADMIN).build());
        c.register(technical(TEST_MESSAGE, "Test message").schedule(Schedule.immediately()).build());
        c.register(EventType.builder(DELIVERY_APPROVAL_WAITING.value(), "Case waiting for delivery approval",
                EventKind.BUSINESS).schedule(Schedule.immediately()).recipients(SUPERVISOR)
                .time("expiresAt", "Links work until").build());
        c.register(outcome(DELIVERY_APPROVED, "Delivery approved"));
        c.register(outcome(DELIVERY_DECLINED, "Delivery declined"));
        return c.build();
    }

    private static EventType.Builder technical(EventTypeId id, String name) {
        return EventType.builder(id.value(), name, EventKind.TECHNICAL).recipients(IT_ADMIN);
    }

    private static EventType work(EventTypeId id, String name, RoleId... roles) {
        return EventType.builder(id.value(), name, EventKind.BUSINESS).schedule(Schedule.every4Hours()).recipients(roles)
                .continuous(EVERY_4_HOURS).count("cases", "Cases").build();
    }

    private static EventType outcome(EventTypeId id, String name) {
        return EventType.builder(id.value(), name, EventKind.BUSINESS).schedule(Schedule.immediately())
                .recipients(SUPERVISOR).build();
    }
}
