package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.ActionLinks;
import com.documentplatform.notifications.ConditionStore;
import com.documentplatform.notifications.CustomerZones;
import com.documentplatform.notifications.EntityZones;
import com.documentplatform.notifications.EventCatalogue;
import com.documentplatform.notifications.NotificationSettings;
import com.documentplatform.notifications.Notifier;
import com.documentplatform.notifications.OutboxStore;
import com.documentplatform.notifications.SettingsStore;
import com.documentplatform.notifications.SummaryStore;
import com.documentplatform.notifications.approval.ApprovalStore;
import com.documentplatform.notifications.approval.ApprovalType;
import com.documentplatform.notifications.email.EmailChannel;
import com.documentplatform.notifications.email.MicrosoftTokens;
import com.documentplatform.notifications.jdbc.NotificationSchema;
import com.documentplatform.notifications.jdbc.PostgresApprovalStore;
import com.documentplatform.notifications.jdbc.PostgresConditionStore;
import com.documentplatform.notifications.jdbc.PostgresOutboxStore;
import com.documentplatform.notifications.jdbc.PostgresSettingsStore;
import com.documentplatform.notifications.jdbc.PostgresSummaryStore;
import com.documentplatform.outbound.AddressGuard;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.TrustBundle;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.EntitySlotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import javax.net.ssl.SSLSocketFactory;
import javax.sql.DataSource;
import java.time.Clock;
import java.time.DateTimeException;
import java.time.ZoneId;
import java.util.Optional;

/**
 * Task 0059: platform-notifications in LexaVault. The platform's own wiring runs the workers, the approval links and the checks
 * of {@code platform.notifications.*}; LexaVault gives it its catalogue, people, secrets, stores and one mail server per entity
 * (the platform's {@code platform.notifications.mail.*} would be one for everybody, so it is not used).
 */
@Configuration
@EnableConfigurationProperties(NotificationsProperties.class)
@NotificationsOn
public class LexaVaultNotificationsConfig {

    /** The data source of the stores, once the {@code notif_} tables passed the check. */
    record Tables(DataSource dataSource) {
    }

    @Bean
    Tables notificationTablesChecked(DataSource dataSource, NotificationsProperties properties) {
        properties.check();
        NotificationSchema.verify(dataSource);
        return new Tables(new JoiningDataSource(dataSource));
    }

    @Bean
    EventCatalogue lexaVaultEventCatalogue() {
        return LexaVaultEvents.catalogue();
    }

    @Bean
    NotificationPeople notificationPeople(JdbcTemplate jdbc, PlatformTransactionManager transactions,
                                          NotificationsProperties properties) {
        return new NotificationPeople(jdbc, new TransactionTemplate(transactions), properties.getPeopleSeenWithin(),
                Clock.systemUTC());
    }

    @Bean
    SettingsStore notificationEntitySettings(Tables tables, MailSettingsStore mail, NotificationsProperties properties) {
        return new EntityNotifications.Settings(new PostgresSettingsStore(tables.dataSource()), mail, properties.appAddress());
    }

    @Bean
    OutboxStore notificationEntityOutbox(Tables tables, MailSettingsStore mail) {
        return new EntityNotifications.Outbox(new PostgresOutboxStore(tables.dataSource()), mail);
    }

    @Bean
    SummaryStore notificationSummaries(Tables tables) {
        return new PostgresSummaryStore(tables.dataSource());
    }

    @Bean
    ConditionStore notificationConditions(Tables tables) {
        return new PostgresConditionStore(tables.dataSource());
    }

    @Bean
    ApprovalStore notificationApprovals(Tables tables) {
        return new PostgresApprovalStore(tables.dataSource());
    }

    @Bean
    CustomerZones notificationCustomerZones(EntitySlotStore slots, JdbcTemplate jdbc) {
        return tenant -> {
            var entity = EntityNotifications.uuid(tenant);
            if (entity.isPresent()) return slots.effective(TenantId.of(entity.get()));
            return jdbc.queryForList("select time_zone from idn_customer where tenant_id = ?", String.class, tenant.value())
                    .stream().findFirst().flatMap(LexaVaultNotificationsConfig::zone);
        };
    }

    @Bean
    EntityZones notificationEntityZones(EntitySlotStore slots) {
        return (tenant, entity) -> EntityNotifications.uuid(entity.value()).map(TenantId::of).flatMap(slots::effective);
    }

    private static Optional<ZoneId> zone(String id) {
        try {
            return id == null ? Optional.empty() : Optional.of(ZoneId.of(id));
        } catch (DateTimeException unknown) {
            return Optional.empty();
        }
    }

    @Bean
    AddressGuard notificationAddressGuard(Installation installation) {
        return new AddressGuard(installation.allowInternalAddresses());
    }

    @Bean
    EntityMailServers entityMailServers(MailSettingsStore mail, Installation installation, AddressGuard notificationAddressGuard,
                                        NotificationsProperties properties) {
        return new EntityMailServers(mail::find, installation, notificationAddressGuard, properties.getMailPorts(),
                EntityMailServers.MicrosoftEndpoints.at(properties.getMicrosoftAuthority()));
    }

    /**
     * The sender. As the platform's own, with a mail server per entity. A message goes out at the sender's next round (10
     * seconds by default): the platform's wake-up signal is not open to apps.
     */
    @Bean
    Notifier notifier(NotificationSettings settings, NotificationPeople people, OutboxStore outbox, SummaryStore summaries,
                      ConditionStore conditions, EntityMailServers servers, AppSecretResolver secrets, MicrosoftTokens tokens,
                      Installation installation, com.documentplatform.notifications.spring.NotificationsProperties platform,
                      ObjectProvider<ActionLinks> links, EntityZones entityZones, CustomerZones customerZones) {
        var privateCas = installation.privateCaCertificates();
        SSLSocketFactory tls = privateCas.isEmpty() ? null : TrustBundle.context(privateCas).getSocketFactory();
        Notifier.Builder b = Notifier.builder(settings, people)
                .channel(new EmailChannel(servers, secrets, tls, tokens))
                .outbox(outbox).summaries(summaries).conditions(conditions)
                .retryPolicy(new com.documentplatform.notifications.RetryPolicy(platform.getRetry().getFirstDelay(),
                        platform.getRetry().getMaxDelay(), platform.getRetry().getGiveUpAfter()))
                .lease(platform.getLease()).keepFinishedFor(platform.getRetention())
                .batchSize(platform.getSender().getBatchSize())
                .entityZones(entityZones).customerZones(customerZones);
        links.ifUnique(b::actionLinks);
        return b.build();
    }

    /** Who may decide is {@link ApproverOfThisRequest}; the audit of it {@link ApprovalLinkAudit}. */
    @Bean
    ApprovalType deliveryApprovalType() {
        return LexaVaultEvents.DELIVERY_APPROVAL;
    }
}
