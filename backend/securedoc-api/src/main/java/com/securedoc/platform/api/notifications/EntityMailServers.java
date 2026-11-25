package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.DestinationId;
import com.documentplatform.notifications.TenantId;
import com.documentplatform.notifications.email.MailSecurity;
import com.documentplatform.notifications.email.MailServer;
import com.documentplatform.notifications.email.MailServerDirectory;
import com.documentplatform.notifications.email.MicrosoftOAuth;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.outbound.AddressGuard;
import com.documentplatform.outbound.Installation;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * Task 0059: an entity's mail server, from its own settings, checked at each send as at saving:
 * <ul>
 *   <li>the server's address under the installation's outbound rules (never cloud metadata or link-local; internal addresses
 *       only where {@code platform.outbound.allow-internal-addresses} is on), and only the allowed mail ports;</li>
 *   <li>encrypted, except a relay without a login on an internal address;</li>
 *   <li>only this entity's own secrets.</li>
 * </ul>
 * Microsoft 365 always uses Microsoft's own servers (the sign-in address only from the install settings, for a national
 * cloud). What breaks a rule is not sent at all.
 */
public final class EntityMailServers implements MailServerDirectory {

    private static final Logger log = LoggerFactory.getLogger(EntityMailServers.class);
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9](?:[A-Za-z0-9.-]{0,251}[A-Za-z0-9])?|\\[[0-9A-Fa-f:.]{2,45}]");
    private static final Pattern ADDRESS = Pattern.compile("[^@\\s,;<>\"]{1,64}@[A-Za-z0-9.-]{1,253}");

    private final Function<com.securedoc.platform.domain.identity.TenantId, Optional<MailSettings>> settings;
    private final Installation installation;
    private final AddressGuard guard;
    private final Set<Integer> ports;
    private final MicrosoftEndpoints microsoft;

    /** Where Microsoft 365 is: the sign-in address, and the mail server (its own, except in tests). */
    public record MicrosoftEndpoints(java.net.URI authority, String host, int port, MailSecurity security) {
        public static MicrosoftEndpoints at(java.net.URI authority) {
            return new MicrosoftEndpoints(authority, MicrosoftOAuth.SMTP_HOST, MicrosoftOAuth.SMTP_PORT, MailSecurity.STARTTLS);
        }
    }

    /** A setting that breaks a rule: which field, never what was typed. */
    public static final class Refused extends RuntimeException {
        private final String field;

        Refused(String field, String message) {
            super(message);
            this.field = field;
        }

        public String field() {
            return field;
        }
    }

    public EntityMailServers(Function<com.securedoc.platform.domain.identity.TenantId, Optional<MailSettings>> settings,
                             Installation installation, AddressGuard guard, Set<Integer> ports, MicrosoftEndpoints microsoft) {
        this.settings = Objects.requireNonNull(settings);
        this.installation = Objects.requireNonNull(installation);
        this.guard = Objects.requireNonNull(guard);
        this.ports = Set.copyOf(ports);
        this.microsoft = Objects.requireNonNull(microsoft);
    }

    @Override
    public Optional<MailServer> find(TenantId tenant, DestinationId destination) {
        Optional<UUID> entity = EntityNotifications.uuid(tenant);
        if (entity.isEmpty() || !EntityNotifications.EMAIL.equals(destination)) return Optional.empty();
        Optional<MailSettings> found = settings.apply(com.securedoc.platform.domain.identity.TenantId.of(entity.get()))
                .filter(MailSettings::enabled);
        if (found.isEmpty()) return Optional.empty();
        try {
            return Optional.of(server(found.get()));
        } catch (Refused refused) {
            log.warn("NOTIFICATION_MAIL_REFUSED entity={} field={}", entity.get(), refused.field());
            return Optional.empty();
        }
    }

    /** The server as the sender uses it. @throws Refused naming the field that breaks a rule */
    public MailServer server(MailSettings s) {
        if (s.fromAddress() == null || !ADDRESS.matcher(s.fromAddress()).matches()) {
            throw new Refused("fromAddress", "The sender address is not an e-mail address");
        }
        if (s.fromName() != null && (s.fromName().isBlank() || s.fromName().length() > 100
                || s.fromName().chars().anyMatch(Character::isISOControl))) {
            throw new Refused("fromName", "The sender name is not valid");
        }
        return s.mode() == MailSettings.Mode.MICROSOFT_365 ? microsoft(s) : own(s);
    }

    private MailServer microsoft(MailSettings s) {
        if (!MicrosoftOAuth.isDirectoryId(s.directoryId())) {
            throw new Refused("directoryId", "The directory (tenant) ID is not valid");
        }
        if (!MicrosoftOAuth.isClientId(s.clientId())) {
            throw new Refused("clientId", "The application (client) ID is not valid");
        }
        if (s.credentialType() == null) throw new Refused("credentialType", "Choose certificate or client secret");
        SecretRef credential = secret(s);
        if (!"https".equals(microsoft.authority().getScheme()) && !installation.allowPlainHttp()) {
            throw new Refused("mode", "The Microsoft sign-in address of this installation is not https");
        }
        MicrosoftOAuth oauth = new MicrosoftOAuth(s.directoryId(), s.clientId(), s.credentialType(), credential,
                microsoft.authority());
        MailServer server = MailServer.microsoft365(s.fromAddress(), oauth)
                .withEndpoint(microsoft.host(), microsoft.port(), microsoft.security());
        return s.fromName() == null ? server : server.withFromName(s.fromName());
    }

    private MailServer own(MailSettings s) {
        if (s.host() == null || !HOST.matcher(s.host()).matches()) throw new Refused("host", "The server name is not valid");
        if (s.port() == null || !ports.contains(s.port())) {
            throw new Refused("port", "The port is not one of the mail ports this installation allows");
        }
        if (s.security() == null) throw new Refused("security", "Choose the security of the connection");
        boolean login = s.username() != null;
        if (login && (s.username().isBlank() || s.username().length() > 320
                || s.username().chars().anyMatch(Character::isISOControl))) {
            throw new Refused("username", "The user name is not valid");
        }
        if (s.security() == MailSecurity.NONE && (login || !installation.allowInternalAddresses())) {
            throw new Refused("security", "Only a relay without a login, inside your own network, may be used without encryption");
        }
        if (guard.check(s.host(), false).isPresent()) {
            throw new Refused("host", "The server's address is not known, or this installation may not call it");
        }
        if (!login) {
            if (s.secretReference() != null) throw new Refused("username", "A password needs its user name");
            MailServer relay = MailServer.relay(s.host(), s.port(), s.security(), s.fromAddress());
            return s.fromName() == null ? relay : relay.withFromName(s.fromName());
        }
        MailServer server = MailServer.withLogin(s.host(), s.port(), s.security(), s.username(), secret(s), s.fromAddress());
        return s.fromName() == null ? server : server.withFromName(s.fromName());
    }

    private static SecretRef secret(MailSettings s) {
        try {
            return AppSecretResolver.entityRef(s.entity(), s.secretReference());
        } catch (IllegalArgumentException notOurs) {
            throw new Refused("secretReference", "The secret's name is not one of this entity's");
        }
    }
}
