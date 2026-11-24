package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.ChannelKind;
import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.EntityId;
import com.documentplatform.notifications.EventSettings;
import com.documentplatform.notifications.EventTypeId;
import com.documentplatform.notifications.FailureCode;
import com.documentplatform.notifications.OutboxEntry;
import com.documentplatform.notifications.OutboxStore;
import com.documentplatform.notifications.SettingsStore;
import com.documentplatform.notifications.TenantSettings;
import com.securedoc.platform.domain.identity.TenantId;

import java.net.URI;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0059: in LexaVault every entity is a notification tenant of its own. Its mail server, event settings, people, outbox
 * messages, summaries and approval links (whose signature covers the tenant) are only its own.
 *
 * <p>One sender works for the whole customer: platform-licensing publishes with the customer's id and, for most events, the
 * entity. {@link Outbox} turns each such message into one of that entity, or one per connected entity when the licence as a
 * whole is meant, before it is saved. So nothing is ever sent as the customer.
 */
public final class EntityNotifications {

    /** An entity's one e-mail destination. */
    public static final DestinationId EMAIL = DestinationId.of("email");

    private EntityNotifications() {
    }

    public static com.documentplatform.notifications.TenantId tenant(TenantId entity) {
        return com.documentplatform.notifications.TenantId.of(entity.value().toString());
    }

    public static EntityId entity(TenantId entity) {
        return EntityId.of(entity.value().toString());
    }

    /** The entity a notification tenant is; empty for the customer's id. */
    static Optional<UUID> uuid(com.documentplatform.notifications.TenantId tenant) {
        return uuid(tenant.value());
    }

    static Optional<UUID> uuid(String value) {
        try {
            UUID id = UUID.fromString(value);
            return id.toString().equals(value) ? Optional.of(id) : Optional.empty();
        } catch (IllegalArgumentException notAnId) {
            return Optional.empty();
        }
    }

    /** Where an entity's messages go: its own e-mail, when it is on and the app's address is set. */
    static final class Settings implements SettingsStore {

        private final SettingsStore events;
        private final MailSettingsStore mail;
        private final URI appBaseUrl;

        /** @param appBaseUrl from the install settings, never from a screen; null when not set (nothing is sent) */
        Settings(SettingsStore events, MailSettingsStore mail, URI appBaseUrl) {
            this.events = Objects.requireNonNull(events);
            this.mail = Objects.requireNonNull(mail);
            this.appBaseUrl = appBaseUrl;
        }

        @Override
        public Optional<TenantSettings> tenant(com.documentplatform.notifications.TenantId tenant) {
            if (appBaseUrl == null) return Optional.empty();
            Optional<UUID> entity = uuid(tenant);
            boolean connected = entity.isPresent() ? mail.enabled(TenantId.of(entity.get())) : !mail.connected().isEmpty();
            return connected ? Optional.of(new TenantSettings(appBaseUrl, ZoneOffset.UTC, Map.of(EMAIL, ChannelKind.EMAIL)))
                    : Optional.empty();
        }

        @Override
        public void saveTenant(com.documentplatform.notifications.TenantId tenant, TenantSettings settings) {
            throw new UnsupportedOperationException("An entity's channel is its mail settings");
        }

        @Override
        public Optional<EventSettings> technical(com.documentplatform.notifications.TenantId tenant, EventTypeId type) {
            return uuid(tenant).isEmpty() ? Optional.empty() : events.technical(tenant, type);
        }

        @Override
        public void saveTechnical(com.documentplatform.notifications.TenantId tenant, EventTypeId type,
                                  EventSettings settings) {
            events.saveTechnical(tenant, type, settings);
        }

        @Override
        public Optional<EventSettings> business(com.documentplatform.notifications.TenantId tenant, EntityId entity,
                                                EventTypeId type) {
            // An event of the customer's about one entity follows that entity's own choice.
            if (uuid(tenant).isEmpty()) {
                return uuid(entity.value()).isEmpty() ? Optional.empty()
                        : events.business(com.documentplatform.notifications.TenantId.of(entity.value()), entity, type);
            }
            return tenant.value().equals(entity.value()) ? events.business(tenant, entity, type) : Optional.empty();
        }

        @Override
        public void saveBusiness(com.documentplatform.notifications.TenantId tenant, EntityId entity, EventTypeId type,
                                 EventSettings settings) {
            if (!tenant.value().equals(entity.value())) {
                throw new IllegalArgumentException("Business events are set in the entity they are for");
            }
            events.saveBusiness(tenant, entity, type, settings);
        }
    }

    /** Saves every message as one entity's. */
    static final class Outbox implements OutboxStore {

        private final OutboxStore store;
        private final MailSettingsStore mail;

        Outbox(OutboxStore store, MailSettingsStore mail) {
            this.store = Objects.requireNonNull(store);
            this.mail = Objects.requireNonNull(mail);
        }

        @Override
        public boolean enqueue(OutboxEntry entry) {
            if (uuid(entry.tenant()).isPresent()) {
                // Fail closed: an entity's message about another entity is never saved.
                if (entry.entity() != null && !entry.entity().value().equals(entry.tenant().value())) {
                    throw new IllegalArgumentException("A message belongs to one entity");
                }
                return store.enqueue(entry);
            }
            if (entry.entity() != null) {
                Optional<UUID> entity = uuid(entry.entity().value());
                return entity.isPresent() && mail.enabled(TenantId.of(entity.get()))
                        && store.enqueue(as(entry, TenantId.of(entity.get()), entry.key()));
            }
            boolean saved = false;
            for (TenantId entity : mail.connected()) {
                saved |= store.enqueue(as(entry, entity, entry.key() + "." + entity.value().toString().replace("-", "")));
            }
            return saved;
        }

        private static OutboxEntry as(OutboxEntry e, TenantId entity, String key) {
            return new OutboxEntry(key, tenant(entity), e.destination(), e.channel(), entity(entity), e.recipientRoles(),
                    e.eventTypes(), e.message(), e.state(), e.attempts(), e.createdAt(), e.nextAttemptAt(), e.lastFailure(),
                    e.finishedAt(), e.recipientUser());
        }

        @Override
        public Optional<OutboxEntry> claim(String key, Instant now, Instant leaseUntil) {
            return store.claim(key, now, leaseUntil);
        }

        @Override
        public List<OutboxEntry> claimDue(Instant now, Instant leaseUntil, int limit) {
            return store.claimDue(now, leaseUntil, limit);
        }

        @Override
        public void markSent(String key, int attempts, Instant at) {
            store.markSent(key, attempts, at);
        }

        @Override
        public void markRetry(String key, int attempts, Instant nextAttemptAt, FailureCode failure) {
            store.markRetry(key, attempts, nextAttemptAt, failure);
        }

        @Override
        public void markFailed(String key, int attempts, FailureCode failure, Instant at) {
            store.markFailed(key, attempts, failure, at);
        }

        @Override
        public Optional<OutboxEntry> find(String key) {
            return store.find(key);
        }

        @Override
        public int purgeFinishedBefore(Instant before) {
            return store.purgeFinishedBefore(before);
        }
    }
}
