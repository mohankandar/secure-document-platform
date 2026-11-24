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
    public static final EventTypeId TEST_MESSAGE = EventTypeId.of("notifications.test-message");
    public static final EventTypeId DELIVERY_APPROVAL_WAITING = EventTypeId.of("approval.delivery-waiting");
    public static final EventTypeId DELIVERY_APPROVED = EventTypeId.of("approval.delivery-approved");
    public static final EventTypeId DELIVERY_DECLINED = EventTypeId.of("approval.delivery-declined");

    /** What {@link OperationalNotices} raises and clears, each a condition with one Recovered message. */
    public static final List<EventTypeId> OPERATIONAL = List.of(DESTINATION_PAUSED, DESTINATION_NEEDS_IT, DESTINATION_BUSY,
            DESTINATIONS_UNREACHABLE, EXPORTS_NEED_IT, STORAGE_STOPPED);

    /** Approve delivery, by the Supervisor, as on the case. Links work for 24 hours. */
    public static final ApprovalType DELIVERY_APPROVAL = ApprovalType.of("deliver-case", DELIVERY_APPROVAL_WAITING,
            DELIVERY_APPROVED, DELIVERY_DECLINED, SUPERVISOR);

    private static final ConditionTiming AT_ONCE = new ConditionTiming(Duration.ZERO, Duration.ofHours(4));
    private static final ConditionTiming AFTER_30_MINUTES =
            new ConditionTiming(Duration.ofMinutes(30), Duration.ofHours(4));

    private LexaVaultEvents() {
    }

    public static EventCatalogue catalogue() {
        EventCatalogue.Builder c = EventCatalogue.builder(IT_ADMIN);
        LicensingEvents.register(c, IT_ADMIN, BUSINESS_ADMIN);
        c.register(technical(DESTINATION_PAUSED, "Destination paused").critical().continuous(AT_ONCE).build());
        c.register(technical(DESTINATION_NEEDS_IT, "Destination needs IT").critical().continuous(AT_ONCE).build());
        c.register(technical(DESTINATIONS_UNREACHABLE, "Destinations can not be reached").critical().continuous(AT_ONCE)
                .build());
        c.register(technical(EXPORTS_NEED_IT, "Cases waiting for IT").critical().continuous(AT_ONCE)
                .count("problems", "Problems").count("cases", "Cases").build());
        c.register(technical(DESTINATION_BUSY, "Destination busy for more than 30 minutes")
                .schedule(Schedule.immediately()).continuous(AFTER_30_MINUTES).build());
        c.register(technical(STORAGE_STOPPED, "Processing stopped, storage not usable").critical().continuous(AT_ONCE)
                .build());
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

    private static EventType outcome(EventTypeId id, String name) {
        return EventType.builder(id.value(), name, EventKind.BUSINESS).schedule(Schedule.immediately())
                .recipients(SUPERVISOR).build();
    }
}
