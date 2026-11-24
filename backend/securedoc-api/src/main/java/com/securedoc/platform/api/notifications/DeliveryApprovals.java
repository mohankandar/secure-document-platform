package com.securedoc.platform.api.notifications;

import com.documentplatform.notifications.AppLink;
import com.documentplatform.notifications.NotificationException;
import com.documentplatform.notifications.RecordIdentifier;
import com.documentplatform.notifications.SettingsStore;
import com.documentplatform.notifications.approval.ApprovalAction;
import com.documentplatform.notifications.approval.ApprovalRefusal;
import com.documentplatform.notifications.approval.ApprovalRequest;
import com.documentplatform.notifications.approval.ApprovalResult;
import com.documentplatform.notifications.approval.Approvals;
import com.documentplatform.notifications.approval.NewApprovalRequest;
import com.documentplatform.notifications.approval.SignedInPerson;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.lifecycle.command.ApproveFinalDeliveryCommand;
import com.securedoc.platform.application.port.in.ApproveFinalDeliveryUseCase;
import com.securedoc.platform.application.port.out.CasePrimaryIdentifierLookup;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.PackageId;
import com.securedoc.platform.domain.identity.TenantId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Task 0059: approving delivery through a one-click link.
 *
 * <p><b>Asking.</b> A case that waits for delivery approval gets one request per version of it, when its entity's e-mail is on
 * and a Supervisor other than the submitter is known. The message holds no more than the case's internal id and the links.
 *
 * <p><b>Using a link.</b> Opening one decides nothing. The confirming click runs, in one transaction: the link's own checks
 * (signature, this entity, this person, not their own request, the role now, not decided, not expired), then the case is locked
 * and must still wait for approval at exactly the version the link was made for, then the decision is stored (first wins) and
 * the normal approval runs with all its own checks. If any step refuses, all of it is undone, the refusal is audited and the
 * case is as it was. The endpoint also has what every approval has: the capability, and an entity that is not suspended.
 *
 * <p><b>Declining</b> closes the request and tells the submitter. The case itself has no "declined": it stays waiting, and can
 * still be approved on the case.
 */
@NotificationsOn
@Component
public class DeliveryApprovals {

    private static final Logger log = LoggerFactory.getLogger(DeliveryApprovals.class);
    private static final int PER_ROUND = 50;

    /** Why a decline, as fixed texts: free text could hold a value from a document. */
    public enum DeclineReason {
        WRONG_DESTINATION("Wrong destination"), CHECK_REDACTION("The redaction needs another look"),
        NOT_READY("Not ready to be delivered"), OTHER("Another reason, told to the team directly");

        private final String text;

        DeclineReason(String text) {
            this.text = text;
        }
    }

    /**
     * What the page shows.
     *
     * @param caseNumber as the Cases list shows it, for this signed-in Supervisor only (never in a message); READY only
     * @param state   READY, APPROVED, DECLINED, ALREADY_DECIDED or REFUSED
     * @param code    the refusal's code; null otherwise
     * @param message a fixed text
     */
    public record Answer(String state, String action, UUID caseId, String caseNumber, Instant expiresAt, String code,
                         String message) {
    }

    private static final class Undo extends RuntimeException {
        private final Answer answer;
        private final ApprovalRequest request;
        private final PackageId caseId;

        Undo(Answer answer, ApprovalRequest request, PackageId caseId) {
            super(answer.code(), null, false, false);
            this.answer = answer;
            this.request = request;
            this.caseId = caseId;
        }
    }

    private record Bound(PackageId caseId, long version) {
    }

    private final ObjectProvider<Approvals> approvals;
    private final ApproveFinalDeliveryUseCase approve;
    private final SettingsStore channels;
    private final NotificationPeople people;
    private final ApprovalLinkAudit audit;
    private final CasePrimaryIdentifierLookup caseNumbers;
    private final JdbcTemplate jdbc;
    private final TransactionTemplate transaction;

    public DeliveryApprovals(ObjectProvider<Approvals> approvals, ApproveFinalDeliveryUseCase approve, SettingsStore channels,
                             NotificationPeople people, ApprovalLinkAudit audit, CasePrimaryIdentifierLookup caseNumbers,
                             JdbcTemplate jdbc,
                             PlatformTransactionManager transactions) {
        this.approvals = approvals;
        this.approve = approve;
        this.channels = channels;
        this.people = people;
        this.audit = audit;
        this.caseNumbers = caseNumbers;
        this.jdbc = jdbc;
        this.transaction = new TransactionTemplate(transactions);
    }

    /** Whether links are made at all: the link key is set ({@code platform.notifications.approvals.link-key-secret}). */
    public boolean on() {
        return approvals.getIfAvailable() != null;
    }

    // ------------------------------------------------------------------ asking

    private record Waiting(UUID id, UUID entity, long version, UUID submittedBy) {
    }

    @Scheduled(fixedDelayString = "${securedoc.notifications.check-interval-ms:60000}", initialDelayString = "${securedoc.notifications.check-interval-ms:60000}")
    public void askForWaitingCases() {
        if (!on()) return;
        try {
            for (Waiting w : jdbc.query("select p.id, p.tenant_id, p.version, p.created_by from document_packages p"
                    + " join notification_mail_settings m on m.tenant_id = p.tenant_id and m.enabled"
                    + " where p.status = 'PENDING_FINAL_APPROVAL' and not exists (select 1 from delivery_approval_requests r"
                    + " where r.package_id = p.id and r.case_version = p.version) order by p.updated_at limit ?",
                    (rs, i) -> new Waiting(rs.getObject(1, UUID.class), rs.getObject(2, UUID.class), rs.getLong(3),
                            rs.getObject(4, UUID.class)), PER_ROUND)) {
                ask(w);
            }
        } catch (RuntimeException e) {
            log.warn("DELIVERY_APPROVAL_ASKING_FAILED type={}", e.getClass().getSimpleName());
        }
    }

    private void ask(Waiting w) {
        TenantId entity = TenantId.of(w.entity());
        // Active, its e-mail on and the app's address set; otherwise the case waits to be asked about.
        if (channels.tenant(EntityNotifications.tenant(entity)).isEmpty()) return;
        String submitter = w.submittedBy().toString();
        // Nobody to ask yet (nobody signed in lately, or only the submitter): asked as soon as there is.
        if (people.resolve(EntityNotifications.tenant(entity), EntityNotifications.entity(entity), LexaVaultEvents.SUPERVISOR)
                .stream().allMatch(p -> p.userId().equals(submitter))) return;
        try {
            transaction.executeWithoutResult(status -> {
                // Locked, so a second server asks once; nothing of the case is changed.
                List<Long> still = jdbc.queryForList("select version from document_packages where id = ? and tenant_id = ?"
                        + " and version = ? and status = 'PENDING_FINAL_APPROVAL' for update skip locked", Long.class, w.id(),
                        w.entity(), w.version());
                if (still.isEmpty() || Boolean.TRUE.equals(jdbc.queryForObject("select exists (select 1 from"
                        + " delivery_approval_requests where package_id = ? and case_version = ?)", Boolean.class, w.id(),
                        w.version()))) return;
                Approvals.Created made = approvals.getObject().create(new NewApprovalRequest(
                        LexaVaultEvents.DELIVERY_APPROVAL.id(), EntityNotifications.tenant(entity),
                        EntityNotifications.entity(entity), submitter, RecordIdentifier.of(w.id().toString()),
                        AppLink.of("/case/" + w.id())));
                jdbc.update("insert into delivery_approval_requests (request_id, tenant_id, package_id, case_version, created_at)"
                        + " values (?, ?, ?, ?, ?)", made.request().id(), w.entity(), w.id(), w.version(),
                        Timestamp.from(made.request().createdAt()));
            });
        } catch (RuntimeException e) {
            log.warn("DELIVERY_APPROVAL_NOT_ASKED package={} type={}", w.id(), e.getClass().getSimpleName());
        }
    }

    /** Old people rows, and the bindings of cases that no longer wait. */
    @Scheduled(fixedDelay = 24 * 3600_000L, initialDelay = 600_000L)
    public void removeOld() {
        try {
            people.purge();
            jdbc.update("delete from delivery_approval_requests r where r.created_at < now() - interval '31 days'"
                    + " and not exists (select 1 from document_packages p where p.id = r.package_id"
                    + " and p.version = r.case_version and p.status = 'PENDING_FINAL_APPROVAL')");
        } catch (RuntimeException e) {
            log.warn("NOTIFICATION_CLEANUP_FAILED type={}", e.getClass().getSimpleName());
        }
    }

    // ------------------------------------------------------------------ using a link

    /** For the page: what the link is for. Decides nothing and changes nothing. */
    public Answer open(AuthenticatedPrincipal principal, String token) {
        if (!on() || token == null) return refusedOpen(principal, "unknown", ApprovalRefusal.LINK_INVALID.name());
        ApprovalResult result = approvals.getObject().open(token, person(principal));
        if (result instanceof ApprovalResult.Ready ready) {
            Bound bound = bound(principal.tenantId(), ready.request());
            if (bound == null || !waitsAt(principal.tenantId(), bound, false)) {
                return refusedOpen(principal, ready.request().id().toString(), "CASE_CHANGED");
            }
            String number = Optional.ofNullable(caseNumbers.findByPackageIds(principal.tenantId(), List.of(bound.caseId()))
                    .get(bound.caseId())).map(CasePrimaryIdentifierLookup.CasePrimaryIdentifier::value).orElse(null);
            return new Answer("READY", ready.action().name(), bound.caseId().value(), number, ready.request().expiresAt(), null, "");
        }
        if (result instanceof ApprovalResult.AlreadyDecided decided) return already(decided.request());
        ApprovalRefusal refusal = ((ApprovalResult.Refused) result).refusal();
        return refusedOpen(principal, "unknown", refusal.name());
    }

    /** The confirming click. */
    public Answer decide(AuthenticatedPrincipal principal, CorrelationId correlation, String token, String reasonCode) {
        if (!on() || token == null) return refusedOpen(principal, "unknown", ApprovalRefusal.LINK_INVALID.name());
        String reason = null;
        if (reasonCode != null && !reasonCode.isBlank()) {
            try {
                reason = DeclineReason.valueOf(reasonCode.strip().toUpperCase(Locale.ROOT)).text;
            } catch (IllegalArgumentException unknown) {
                return refusedOpen(principal, "unknown", ApprovalRefusal.REASON_INVALID.name());
            }
        }
        String given = reason;
        try {
            return transaction.execute(status -> decideHere(principal, correlation, token, given));
        } catch (Undo undo) {
            audit.refused(principal.tenantId(), principal.actorId(), undo.request.id().toString(), undo.caseId,
                    undo.answer.action(), undo.answer.code());
            return undo.answer;
        }
    }

    private Answer decideHere(AuthenticatedPrincipal principal, CorrelationId correlation, String token, String reason) {
        Approvals links = approvals.getObject();
        SignedInPerson person = person(principal);
        ApprovalResult looked = links.open(token, person);
        Bound bound = null;
        if (looked instanceof ApprovalResult.Ready ready) {
            bound = bound(principal.tenantId(), ready.request());
            if (bound == null || !waitsAt(principal.tenantId(), bound, true)) {
                throw new Undo(refusal(ready.action().name(), "CASE_CHANGED"), ready.request(),
                        bound == null ? null : bound.caseId());
            }
        }
        // All of the link's checks again; the first decision wins in the database. Audited by the platform's callback.
        ApprovalResult result = links.decide(token, person, reason);
        if (result instanceof ApprovalResult.Refused refused) return refusal(null, refused.refusal().name());
        if (result instanceof ApprovalResult.AlreadyDecided decided) return already(decided.request());
        ApprovalRequest request = ((ApprovalResult.Decided) result).request();
        if (bound == null) throw new Undo(refusal(null, "CASE_CHANGED"), request, null);
        if (request.decision().action() == ApprovalAction.DECLINE) {
            return new Answer("DECLINED", ApprovalAction.DECLINE.name(), bound.caseId().value(), null, null, null,
                    "Declined. The case stays where it is; nothing was delivered.");
        }
        try {
            approve.execute(new ApproveFinalDeliveryCommand(bound.caseId(), principal.tenantId(), principal.actorId(), correlation));
        } catch (RuntimeException refused) {
            log.warn("DELIVERY_APPROVAL_LINK_REFUSED package={} type={}", bound.caseId(), refused.getClass().getSimpleName());
            throw new Undo(refusal(ApprovalAction.APPROVE.name(), "APPROVAL_REFUSED"), request, bound.caseId());
        }
        return new Answer("APPROVED", ApprovalAction.APPROVE.name(), bound.caseId().value(), null, null, null,
                "Approved. The case is being delivered.");
    }

    private Bound bound(TenantId entity, ApprovalRequest request) {
        if (!request.entity().value().equals(entity.value().toString())
                || !LexaVaultEvents.DELIVERY_APPROVAL.id().equals(request.type())) return null;
        List<Bound> found = jdbc.query("select package_id, case_version from delivery_approval_requests where request_id = ?"
                + " and tenant_id = ?", (rs, i) -> new Bound(PackageId.of(rs.getObject(1, UUID.class)), rs.getLong(2)),
                request.id(), entity.value());
        return found.size() == 1 ? found.get(0) : null;
    }

    private boolean waitsAt(TenantId entity, Bound bound, boolean lock) {
        return jdbc.queryForList("select 1 from document_packages where id = ? and tenant_id = ? and version = ?"
                + " and status = 'PENDING_FINAL_APPROVAL'" + (lock ? " for update" : ""), Integer.class,
                bound.caseId().value(), entity.value(), bound.version()).size() == 1;
    }

    private Answer refusedOpen(AuthenticatedPrincipal principal, String request, String code) {
        audit.refused(principal.tenantId(), principal.actorId(), request, null, "OPEN", code);
        return refusal(null, code);
    }

    private static Answer already(ApprovalRequest request) {
        String what = request.decided().map(d -> d.action() == ApprovalAction.APPROVE ? "approved" : "declined").orElse("decided");
        return new Answer("ALREADY_DECIDED", null, null, null, null, "ALREADY_DECIDED", "This request was already " + what + ".");
    }

    private static final Map<String, String> OWN_TEXTS = Map.of(
            "CASE_CHANGED", "The case no longer waits for this approval: it was approved on the case, or it has changed since"
                    + " the message was sent.",
            "APPROVAL_REFUSED", "The case could not be approved. Open the case to see why.");

    private static Answer refusal(String action, String code) {
        String text = OWN_TEXTS.get(code);
        if (text == null) text = ApprovalRefusal.valueOf(code).text();
        if (code.equals(ApprovalRefusal.LINK_INVALID.name())) {
            text += " If you work in more than one entity, switch to the one the case belongs to and open the link again.";
        }
        return new Answer("REFUSED", action, null, null, null, code, text);
    }

    private static SignedInPerson person(AuthenticatedPrincipal principal) {
        try {
            return new SignedInPerson(EntityNotifications.tenant(principal.tenantId()),
                    principal.actorId().value().toString(), shown(principal.username()));
        } catch (NotificationException | IllegalArgumentException odd) {
            return new SignedInPerson(EntityNotifications.tenant(principal.tenantId()),
                    principal.actorId().value().toString(), "User");
        }
    }

    private static String shown(String username) {
        return username != null && username.length() > 200 ? username.substring(0, 200) : username;
    }
}
