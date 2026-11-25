package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.ConditionTiming;
import com.documentplatform.notifications.EventKind;
import com.documentplatform.notifications.EventSettings;
import com.documentplatform.notifications.EventType;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.Frequency;
import com.documentplatform.notifications.NotificationEvent;
import com.documentplatform.notifications.NotificationException;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.Schedule;
import com.documentplatform.notifications.email.MailSecurity;
import com.documentplatform.notifications.email.MicrosoftOAuth;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;

/**
 * Task 0059: the settings of the entity the person is in, and of no other. The IT Admin sets the e-mail and the technical
 * messages, the Business Admin the business ones. A secret is named, never typed: its name is never returned either. Every
 * change is audited with field names and codes, never an address, a login or a name.
 */
@NotificationsOn
@Service
public class NotificationSettingsService {

    /** @param secretReference null keeps the stored one (only while the kind of sign-in stays the same) */
    public record MailRequest(Boolean enabled, String mode, String host, Integer port, String security, String username,
                              String fromAddress, String fromName, String directoryId, String clientId, String credentialType,
                              String secretReference) {
        @Override
        public String toString() {
            return "MailRequest[]";
        }
    }

    public record MailView(boolean enabled, String mode, String host, Integer port, String security, String username,
                           String fromAddress, String fromName, String directoryId, String clientId, String credentialType,
                           boolean secretNamed) {
    }

    public record RoleCount(String role, int people) {
    }

    /** One message's fate, without a recipient or a word of it. */
    public record Delivery(String event, String state, String code, String reason, Instant at) {
    }

    /**
     * @param appAddressSet    whether the installation gave the app's address; without it nothing is sent
     * @param approvalLinksOn  whether the installation gave the link key
     * @param secretNamePrefix what the name of this entity's secrets must start with
     */
    public record ItOverview(MailView mail, boolean appAddressSet, boolean approvalLinksOn, String secretNamePrefix,
                             List<Integer> mailPorts, List<RoleCount> people, List<Delivery> recent, List<EventView> events) {
    }

    public record BusinessOverview(boolean mailOn, List<RoleCount> people, List<EventView> events) {
    }

    /**
     * @param dailyAt           the time of a daily summary (HH:mm), when that is the frequency
     * @param alertAfterMinutes how long a problem lasts before it is told; null for what is not a lasting problem
     * @param repeatMinutes     how often it is told again while it lasts
     * @param alertAfterMax     the longest an admin may choose (60 for a critical one)
     */
    public record EventView(String id, String name, boolean enabled, boolean alwaysOn, String frequency,
                            List<String> frequencies, List<String> roles, String dailyAt, Long alertAfterMinutes,
                            Long repeatMinutes, long alertAfterMax, List<ThresholdView> thresholds) {
    }

    public record ThresholdView(String name, String label, long value, long min, long max) {
    }

    /** A value that is null stays as it is. */
    public record EventRequest(Boolean enabled, String frequency, String dailyAt, Long alertAfterMinutes, Long repeatMinutes,
                               Map<String, Long> thresholds) {
    }

    private final MailSettingsStore mail;
    private final EntityMailServers servers;
    private final NotificationSettings settings;
    private final Notifier notifier;
    private final NotificationPeople people;
    private final DeliveryApprovals approvals;
    private final NotificationsProperties properties;
    private final AuditService audit;
    private final JdbcTemplate jdbc;

    public NotificationSettingsService(MailSettingsStore mail, EntityMailServers servers, NotificationSettings settings,
                                       Notifier notifier, NotificationPeople people, DeliveryApprovals approvals,
                                       NotificationsProperties properties, AuditService audit, JdbcTemplate jdbc) {
        this.mail = mail;
        this.servers = servers;
        this.settings = settings;
        this.notifier = notifier;
        this.people = people;
        this.approvals = approvals;
        this.properties = properties;
        this.audit = audit;
        this.jdbc = jdbc;
    }

    // ------------------------------------------------------------------ IT Admin

    @Transactional(readOnly = true)
    public ItOverview itOverview(AuthenticatedPrincipal principal) {
        TenantId entity = principal.tenantId();
        return new ItOverview(mail.find(entity).map(NotificationSettingsService::view).orElse(null),
                properties.appAddress() != null, approvals.on(), AppSecretResolver.entityPrefix(entity),
                properties.getMailPorts().stream().sorted().toList(), people(entity), recent(entity),
                events(entity, EventKind.TECHNICAL));
    }

    @Transactional
    public void saveMail(AuthenticatedPrincipal principal, CorrelationId correlation, MailRequest r) {
        TenantId entity = principal.tenantId();
        Optional<MailSettings> before = mail.find(entity);
        if (!Boolean.TRUE.equals(r.enabled()) && before.isPresent() && before.get().enabled()) {
            // Switching off always works, whatever else is wrong with the settings; nothing else is changed.
            MailSettings b = before.get();
            mail.save(new MailSettings(entity, false, b.mode(), b.host(), b.port(), b.security(), b.username(), b.fromAddress(),
                    b.fromName(), b.directoryId(), b.clientId(), b.credentialType(), b.secretReference()), principal.actorId(),
                    Instant.now());
            audit.recordSuccess(entity, principal.actorId(), AuditAction.NOTIFICATION_SETTINGS_CHANGED, "NotificationSettings",
                    entity.toString(), correlation, "E-mail switched off");
            return;
        }
        MailSettings.Mode mode = value(MailSettings.Mode.class, r.mode(), "mode");
        boolean microsoft = mode == MailSettings.Mode.MICROSOFT_365;
        MicrosoftOAuth.CredentialType credential = microsoft
                ? value(MicrosoftOAuth.CredentialType.class, r.credentialType(), "credentialType") : null;
        String username = microsoft ? null : blankToNull(r.username());
        String secret = blankToNull(r.secretReference());
        if (secret == null && (microsoft || username != null)) {
            // Kept only for the very same sign-in: a kept secret is never sent to another server or as another user.
            secret = before.filter(b -> b.mode() == mode && b.credentialType() == credential
                    && java.util.Objects.equals(b.username(), username)
                    && (microsoft ? java.util.Objects.equals(b.directoryId(), blankToNull(r.directoryId()))
                            && java.util.Objects.equals(b.clientId(), blankToNull(r.clientId()))
                            : java.util.Objects.equals(b.host(), host(r.host())) && java.util.Objects.equals(b.port(), r.port())))
                    .map(MailSettings::secretReference).orElse(null);
        }
        MailSettings s = new MailSettings(entity, Boolean.TRUE.equals(r.enabled()), mode,
                microsoft ? null : host(r.host()), microsoft ? null : r.port(),
                microsoft ? null : value(MailSecurity.class, r.security(), "security"), username,
                blankToNull(r.fromAddress()), blankToNull(r.fromName()), microsoft ? blankToNull(r.directoryId()) : null,
                microsoft ? blankToNull(r.clientId()) : null, credential, secret);
        try {
            servers.server(s);
        } catch (EntityMailServers.Refused refused) {
            audit.recordDeniedIndependently(entity, principal.actorId(), AuditAction.NOTIFICATION_SETTINGS_CHANGED,
                    "NotificationSettings", entity.toString(), correlation, "E-mail settings refused: field=" + refused.field());
            throw new ValidationException(LvErrorCode.CFG_001, refused.getMessage(), refused.field());
        }
        mail.save(s, principal.actorId(), Instant.now());
        audit.recordSuccess(entity, principal.actorId(), AuditAction.NOTIFICATION_SETTINGS_CHANGED, "NotificationSettings",
                entity.toString(), correlation, "E-mail settings saved: mode=" + mode + ", enabled=" + s.enabled()
                        + (s.security() == null ? "" : ", security=" + s.security()) + ", secretNamed=" + (secret != null));
    }

    /** A test message to the person asking, at the address their own sign-in gave; never to an address someone types. */
    @Transactional
    public void sendTest(AuthenticatedPrincipal principal, CorrelationId correlation) {
        TenantId entity = principal.tenantId();
        String actor = principal.actorId().value().toString();
        if (!mail.enabled(entity)) throw refusal("Save the e-mail settings and switch e-mail on first", "mail");
        if (properties.appAddress() == null) throw refusal("The installation has not set the app's address", "appBaseUrl");
        if (people.person(EntityNotifications.tenant(entity), actor).isEmpty()) {
            throw refusal("Your sign-in gave no e-mail address that can be used", "address");
        }
        notifier.publishToPerson(NotificationEvent.builder(LexaVaultEvents.TEST_MESSAGE, EntityNotifications.tenant(entity))
                .entity(EntityNotifications.entity(entity)).build(), actor);
        audit.recordSuccess(entity, principal.actorId(), AuditAction.NOTIFICATION_TEST_SENT, "NotificationSettings",
                entity.toString(), correlation, "Test message queued");
    }

    // ------------------------------------------------------------------ Business Admin

    @Transactional(readOnly = true)
    public BusinessOverview businessOverview(AuthenticatedPrincipal principal) {
        TenantId entity = principal.tenantId();
        return new BusinessOverview(mail.enabled(entity) && properties.appAddress() != null, people(entity),
                events(entity, EventKind.BUSINESS));
    }

    @Transactional
    public void saveEvent(AuthenticatedPrincipal principal, CorrelationId correlation, EventKind kind, String id, EventRequest r) {
        TenantId entity = principal.tenantId();
        EventType type = shown(kind).stream().filter(t -> t.id().value().equals(id)).findFirst()
                .orElseThrow(() -> refusal("There is no such message", "event"));
        var tenant = EntityNotifications.tenant(entity);
        EventSettings now = settings.effective(tenant, EntityNotifications.entity(entity), type.id());
        Frequency frequency = value(Frequency.class, r.frequency(), "frequency");
        EventSettings changed = now.withEnabled(!Boolean.FALSE.equals(r.enabled())).withSchedule(switch (frequency) {
            case IMMEDIATELY -> Schedule.immediately();
            case EVERY_HOUR -> Schedule.everyHour();
            case EVERY_4_HOURS -> Schedule.every4Hours();
            case ONCE_A_DAY -> Schedule.onceADay(dailyAt(r.dailyAt(), now));
        });
        if (now.conditionTiming() != null) {
            changed = changed.withConditionTiming(new ConditionTiming(
                    r.alertAfterMinutes() == null ? now.conditionTiming().alertAfter() : minutes(r.alertAfterMinutes()),
                    r.repeatMinutes() == null ? now.conditionTiming().repeatEvery() : minutes(r.repeatMinutes())));
        }
        if (r.thresholds() != null) {
            for (Map.Entry<String, Long> t : r.thresholds().entrySet()) {
                if (t.getValue() == null) throw refusal("A number is missing", "thresholds");
                changed = changed.withThreshold(t.getKey(), t.getValue());
            }
        }
        try {
            if (kind == EventKind.TECHNICAL) settings.setTechnicalEvent(tenant, type.id(), changed);
            else settings.setBusinessEvent(tenant, EntityNotifications.entity(entity), type.id(), changed);
        } catch (NotificationException e) {
            throw refusal("That choice is not allowed for this message", "event");
        }
        audit.recordSuccess(entity, principal.actorId(), AuditAction.NOTIFICATION_EVENT_CHANGED, "NotificationEvent",
                type.id().value(), correlation, "Message setting saved: event=" + type.id().value() + ", enabled="
                        + changed.enabled() + ", frequency=" + frequency + (changed.conditionTiming() == null ? ""
                        : ", alertAfterMinutes=" + changed.conditionTiming().alertAfter().toMinutes() + ", repeatMinutes="
                        + changed.conditionTiming().repeatEvery().toMinutes()));
    }

    // ------------------------------------------------------------------ parts

    private static Duration minutes(long value) {
        if (value < 0 || value > 24 * 60) throw refusal("The time is not allowed for this message", "event");
        return Duration.ofMinutes(value);
    }

    private static LocalTime dailyAt(String value, EventSettings now) {
        if (value == null || value.isBlank()) {
            return now.schedule().dailyAt() == null ? LocalTime.of(8, 0) : now.schedule().dailyAt();
        }
        try {
            return LocalTime.parse(value.strip());
        } catch (java.time.DateTimeException notATime) {
            throw refusal("The time of day is written HH:mm", "dailyAt");
        }
    }

    private List<EventType> shown(EventKind kind) {
        return settings.catalogue().all().stream().filter(t -> t.kind() == kind)
                // The customer's own (the licence as a whole) are not set per entity; the test message is a button.
                .filter(t -> kind == EventKind.BUSINESS || LexaVaultEvents.TECHNICAL.contains(t.id())).toList();
    }

    private List<EventView> events(TenantId entity, EventKind kind) {
        return shown(kind).stream().map(t -> {
            EventSettings s = settings.effective(EntityNotifications.tenant(entity), EntityNotifications.entity(entity), t.id());
            // Links end with their request and an outcome is for one person, so these always go at once.
            boolean atOnce = t.id().value().startsWith("approval.");
            List<String> choices = atOnce ? List.of(Frequency.IMMEDIATELY.name())
                    : t.critical() ? List.of(Frequency.IMMEDIATELY.name(), Frequency.EVERY_HOUR.name())
                    : java.util.Arrays.stream(Frequency.values()).map(Enum::name).toList();
            return new EventView(t.id().value(), t.name(), s.enabled(), t.critical(), s.schedule().frequency().name(), choices,
                    s.recipientRoles().stream().map(Object::toString).sorted().toList(),
                    s.schedule().dailyAt() == null ? null : s.schedule().dailyAt().toString(),
                    s.conditionTiming() == null ? null : s.conditionTiming().alertAfter().toMinutes(),
                    s.conditionTiming() == null ? null : s.conditionTiming().repeatEvery().toMinutes(),
                    t.critical() ? 60 : 24 * 60,
                    t.thresholds().stream().map(x -> new ThresholdView(x.name(), x.label(),
                            s.thresholds().getOrDefault(x.name(), x.defaultValue()), x.min(), x.max())).toList());
        }).toList();
    }

    private List<RoleCount> people(TenantId entity) {
        return List.of(LexaVaultEvents.IT_ADMIN, LexaVaultEvents.BUSINESS_ADMIN, LexaVaultEvents.SUPERVISOR,
                LexaVaultEvents.PROCESSOR).stream()
                .map(role -> new RoleCount(role.value(), people.count(entity, role))).toList();
    }

    private List<Delivery> recent(TenantId entity) {
        return jdbc.query("select event_types[1], state, last_failure, coalesce(finished_at, created_at) as at from notif_outbox"
                + " where tenant_id = ? order by seq desc limit 10", (rs, i) -> {
                    String code = rs.getString(3);
                    return new Delivery(settings.catalogue().find(EventTypeId.of(rs.getString(1))).map(EventType::name)
                            .orElse("Message"), rs.getString(2), code, code == null ? null : reason(code),
                            rs.getTimestamp(4).toInstant());
                }, entity.value().toString());
    }

    private static String reason(String code) {
        try {
            return FailureCode.valueOf(code).describe(ChannelKind.EMAIL);
        } catch (IllegalArgumentException later) {
            return null;
        }
    }

    private static MailView view(MailSettings s) {
        return new MailView(s.enabled(), s.mode().name(), s.host(), s.port(), s.security() == null ? null : s.security().name(),
                s.username(), s.fromAddress(), s.fromName(), s.directoryId(), s.clientId(),
                s.credentialType() == null ? null : s.credentialType().name(), s.secretReference() != null);
    }

    private static String host(String host) {
        String h = blankToNull(host);
        return h == null ? null : h.toLowerCase(Locale.ROOT);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }

    private static <E extends Enum<E>> E value(Class<E> type, String name, String field) {
        try {
            return Enum.valueOf(type, name == null ? "" : name.strip());
        } catch (IllegalArgumentException unknown) {
            throw refusal("A choice is missing or not known", field);
        }
    }

    private static ValidationException refusal(String message, String field) {
        return new ValidationException(LvErrorCode.CFG_001, message, field);
    }
}
