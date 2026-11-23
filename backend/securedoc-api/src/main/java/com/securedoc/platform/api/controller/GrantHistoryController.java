package com.securedoc.platform.api.controller;

import com.documentplatform.identity.store.GrantChange;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.infrastructure.identity.PlatformIdentityAdminReads;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;

/**
 * The entity's sign-in access changes (task 0041): platform-identity's grant history ({@code idn_grant_history},
 * identity schema V3), shown on the Audit page next to LexaVault's own audit records. Read-only: which login-system
 * group gained, lost or changed which LexaVault role, when, and through what (setup, with its run ID, or the app).
 *
 * <p>Newest first, at most {@link #MAX_ROWS}. Without {@code from} the last 7 days; without {@code to} up to now.
 * Groups and roles are the login system's and LexaVault's names; no token, claim or secret is stored there.
 */
@RestController
@RequestMapping("/api/v1/audit-records/grant-history")
public class GrantHistoryController {

    static final int MAX_ROWS = 200;
    static final Duration DEFAULT_PERIOD = Duration.ofDays(7);

    private final RequestContextResolver context;
    private final PlatformIdentityAdminReads reads;
    private final Clock clock;

    @Autowired
    public GrantHistoryController(RequestContextResolver context, PlatformIdentityAdminReads reads) {
        this(context, reads, Clock.systemUTC());
    }

    GrantHistoryController(RequestContextResolver context, PlatformIdentityAdminReads reads, Clock clock) {
        this.context = context;
        this.reads = reads;
        this.clock = clock;
    }

    @GetMapping
    @RequireCapability(UserCapability.AUDIT_VIEW)
    public ResponseEntity<List<Row>> list(@RequestParam(required = false) String from,
                                          @RequestParam(required = false) String to,
                                          HttpServletRequest request) {
        UUID entity = context.resolvePrincipal(request).tenantId().value();
        Instant now = clock.instant();
        Instant end = to == null || to.isBlank() ? now.plusSeconds(1) : instant(to, "to");
        Instant start = from == null || from.isBlank() ? end.minus(DEFAULT_PERIOD) : instant(from, "from");
        if (start.isAfter(end)) throw new ValidationException(LvErrorCode.CFG_001, "'from' must not be after 'to'");
        List<GrantChange> changes = new ArrayList<>(reads.grantHistory(entity, start, end));
        Collections.reverse(changes);
        return ResponseEntity.ok(changes.stream().limit(MAX_ROWS).map(Row::of).toList());
    }

    private static Instant instant(String value, String name) {
        try {
            return Instant.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new ValidationException(LvErrorCode.CFG_001, "Invalid ISO-8601 timestamp for parameter '" + name + "'");
        }
    }

    /** One change as the Audit page lists it. */
    public record Row(Instant changedAt, String change, String group, String roleBefore, String roleAfter,
                      String source, String changedBy, String setupRunId, String issuer) {
        static Row of(GrantChange c) {
            return new Row(c.changedAt(), c.change().name(), c.group(), c.roleBefore(), c.roleAfter(),
                    c.source().name(), c.changedBy(), c.setupRunId(), c.issuer().toString());
        }
    }
}
