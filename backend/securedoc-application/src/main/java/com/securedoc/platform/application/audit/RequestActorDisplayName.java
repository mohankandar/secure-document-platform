package com.securedoc.platform.application.audit;

import com.securedoc.platform.domain.identity.ActorId;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * The display name of the person the current request is for, so {@code AuditService} can record it without any
 * call site passing it (task 0016). Framework-neutral: the API layer fills it where the request's principal is
 * resolved and clears it when the request ends. It is bound to the calling thread only; work handed to another
 * thread (worker, schedulers) never sees it.
 *
 * <p>Fail closed: a name is given only for the exact actor it was bound with, never for the system actor, and
 * never when nothing is bound. So a system record written inside a person's request carries no name.
 */
public final class RequestActorDisplayName {

    /** The id LexaVault records for its own automatic work. */
    public static final UUID SYSTEM_ACTOR_ID = UUID.fromString("00000000-0000-0000-0000-000000000000");

    /** Matches the {@code audit_records.actor_display_name} column (VARCHAR 200). */
    public static final int MAX_LENGTH = 200;

    private record Bound(UUID actorId, String displayName) { }

    private final ThreadLocal<Bound> current = new ThreadLocal<>();

    /**
     * Binds the display name for this request: the display-name claim when there is one, otherwise the username.
     * A blank result, or the system actor, binds nothing.
     */
    public void bind(ActorId actorId, String displayNameClaim, String username) {
        current.remove();
        if (actorId == null || SYSTEM_ACTOR_ID.equals(actorId.value())) return;
        String name = clean(displayNameClaim);
        if (name == null) name = clean(username);
        if (name == null) return;
        current.set(new Bound(actorId.value(), name));
    }

    public void clear() {
        current.remove();
    }

    /** The bound display name, only when {@code actorId} is the actor it was bound for. */
    public Optional<String> displayNameFor(ActorId actorId) {
        Bound bound = current.get();
        if (bound == null || actorId == null) return Optional.empty();
        if (SYSTEM_ACTOR_ID.equals(actorId.value()) || !Objects.equals(bound.actorId(), actorId.value())) {
            return Optional.empty();
        }
        return Optional.of(bound.displayName());
    }

    private static String clean(String value) {
        if (value == null) return null;
        String trimmed = value.strip();
        if (trimmed.isEmpty()) return null;
        // Control characters never belong in a name; drop them rather than store them.
        trimmed = trimmed.replaceAll("\\p{Cntrl}", "");
        if (trimmed.isEmpty()) return null;
        return trimmed.length() > MAX_LENGTH ? trimmed.substring(0, MAX_LENGTH) : trimmed;
    }
}
