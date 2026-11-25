package com.securedoc.platform.api.notifications;

import org.springframework.boot.context.properties.ConfigurationProperties;

import java.net.URI;
import java.time.Duration;
import java.util.Set;

/**
 * Task 0059: what the installation sets for notifications, beside platform-notifications' own {@code platform.notifications.*}.
 * The entities' mail servers are set on the screen.
 */
@ConfigurationProperties(prefix = "securedoc.notifications")
public class NotificationsProperties {

    /** Where people reach the app, for example https://lexavault.example. Links in messages start with it. Not set: nothing is sent. */
    private String appBaseUrl;
    /** Who signed in longer ago than this gets no messages. */
    private Duration peopleSeenWithin = Duration.ofDays(14);
    private Set<Integer> mailPorts = Set.of(25, 465, 587, 2525);
    /** Microsoft's sign-in address; another only for a national cloud. */
    private URI microsoftAuthority = URI.create("https://login.microsoftonline.com");
    private long checkIntervalMs = 60_000;

    public String getAppBaseUrl() {
        return appBaseUrl;
    }

    public void setAppBaseUrl(String appBaseUrl) {
        this.appBaseUrl = appBaseUrl;
    }

    public Duration getPeopleSeenWithin() {
        return peopleSeenWithin;
    }

    public void setPeopleSeenWithin(Duration peopleSeenWithin) {
        this.peopleSeenWithin = peopleSeenWithin;
    }

    public Set<Integer> getMailPorts() {
        return mailPorts;
    }

    public void setMailPorts(Set<Integer> mailPorts) {
        this.mailPorts = mailPorts;
    }

    public URI getMicrosoftAuthority() {
        return microsoftAuthority;
    }

    public void setMicrosoftAuthority(URI microsoftAuthority) {
        this.microsoftAuthority = microsoftAuthority;
    }

    public long getCheckIntervalMs() {
        return checkIntervalMs;
    }

    public void setCheckIntervalMs(long checkIntervalMs) {
        this.checkIntervalMs = checkIntervalMs;
    }

    /** The app's address, checked: https, or http on this machine only. Null when not set. */
    public URI appAddress() {
        if (appBaseUrl == null || appBaseUrl.isBlank()) return null;
        try {
            URI uri = URI.create(appBaseUrl.strip());
            boolean local = "http".equals(uri.getScheme()) && ("localhost".equals(uri.getHost()) || "127.0.0.1".equals(uri.getHost()));
            if (uri.getHost() == null || !"https".equals(uri.getScheme()) && !local || uri.getRawUserInfo() != null
                    || uri.getRawQuery() != null || uri.getRawFragment() != null) {
                throw new IllegalArgumentException();
            }
            return uri;
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("NOTIFICATION_SETTINGS_INVALID: securedoc.notifications.app-base-url must be the app's"
                    + " https address (http only for localhost)");
        }
    }

    void check() {
        appAddress();
        if (peopleSeenWithin == null || peopleSeenWithin.compareTo(Duration.ofDays(1)) < 0
                || peopleSeenWithin.compareTo(Duration.ofDays(90)) > 0) {
            throw new IllegalStateException("NOTIFICATION_SETTINGS_INVALID: securedoc.notifications.people-seen-within must be 1 to 90 days");
        }
        if (mailPorts == null || mailPorts.isEmpty() || mailPorts.stream().anyMatch(p -> p == null || p < 1 || p > 65535)) {
            throw new IllegalStateException("NOTIFICATION_SETTINGS_INVALID: securedoc.notifications.mail-ports must be ports");
        }
    }
}
