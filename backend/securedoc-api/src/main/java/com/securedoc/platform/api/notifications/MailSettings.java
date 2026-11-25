package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.email.MailSecurity;
import com.documentplatform.notifications.email.MicrosoftOAuth;
import com.securedoc.platform.domain.identity.TenantId;

/**
 * Task 0059: how one entity's e-mail is sent. A secret is only ever named ({@code secretReference}); its text is never here.
 *
 * @param username the login on the customer's own server, or null for a relay without one; null for Microsoft 365
 */
public record MailSettings(TenantId entity, boolean enabled, Mode mode, String host, Integer port, MailSecurity security,
                           String username, String fromAddress, String fromName, String directoryId, String clientId,
                           MicrosoftOAuth.CredentialType credentialType, String secretReference) {

    public enum Mode { MICROSOFT_365, OWN_SERVER }

    /** Never an address, a login or a reference. */
    @Override
    public String toString() {
        return "MailSettings[" + entity + ", " + mode + ", enabled=" + enabled + "]";
    }
}
