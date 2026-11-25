package com.securedoc.platform.api.identity;

import com.documentplatform.errors.PlatformCode;
import com.documentplatform.identity.store.ActivationOutcome;
import com.documentplatform.identity.store.EntityRecord;
import com.documentplatform.identity.store.EntityStore;
import com.documentplatform.identity.store.EntityTimeZones;
import com.documentplatform.identity.store.LicensedSlots;
import com.documentplatform.identity.store.SlotActivation;
import com.documentplatform.identity.store.TimeZoneOutcome;
import com.documentplatform.licensing.EntitySlot;
import com.securedoc.platform.api.context.RequestContextResolver.VerifiedSignIn;
import com.securedoc.platform.api.licensing.PlatformLicence;
import com.securedoc.platform.application.context.AuthenticatedPrincipal;
import com.securedoc.platform.application.exception.AccessDeniedException;
import com.securedoc.platform.application.port.out.TenantRedactionPolicyRepository;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.EntitySlotStore;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.stream.Collectors;

/**
 * Task 0058: the Business Admin activates, names, suspends and resumes the customer's entity slots and sets an entity's
 * time zone. The rules are platform-identity's ({@link SlotActivation}, {@link EntityTimeZones}); LexaVault adds:
 * <ul>
 *   <li>A Business Admin manages the entity they are in, and other slots of the same customer only where their verified
 *       groups have the Business Admin role by that slot's own grants. A slot they don't manage is neither shown nor
 *       changed, and the refusal does not say whether it exists.</li>
 *   <li>The entity the person is in can't be suspended by them: someone signed in elsewhere must be able to resume it.</li>
 *   <li>LexaVault's own entity row follows the slot in the same transaction.</li>
 * </ul>
 * Every call is audited with its result code, in the log of the entity the person is in and of the entity concerned.
 * Names are never put into the audit detail.
 */
@Service
public class EntitySlotService {

    /** LexaVault's own refusals; the platform's are {@link ActivationOutcome.Result} and {@link TimeZoneOutcome.Result}. */
    static final String ENTITY_IN_USE_HERE = "ENTITY_IN_USE_HERE";

    /** One change's answer. {@code whatToDo} is null when it was done. */
    public record Answer(boolean done, String code, String message, String whatToDo) {
    }

    /** One slot as the screen shows it. */
    public record SlotView(String slotId, String name, String status, boolean current, String timeZone,
                           String customerTimeZone, Instant activatedAt) {
    }

    public record Overview(List<SlotView> entities) {
    }

    private final EntitySlotStore slots;
    private final LicensedSlots licensed;
    private final TenantRedactionPolicyRepository policies;
    private final AuditService audit;
    private final Clock clock;

    @Autowired
    public EntitySlotService(EntitySlotStore slots, PlatformLicence licence, TenantRedactionPolicyRepository policies,
                             AuditService audit) {
        this(slots, licensedSlots(licence), policies, audit, Clock.systemUTC());
    }

    /** For tests: the licence, the setting and the clock given. */
    public EntitySlotService(EntitySlotStore slots, LicensedSlots licensed, TenantRedactionPolicyRepository policies,
                      AuditService audit, Clock clock) {
        this.slots = Objects.requireNonNull(slots);
        this.licensed = Objects.requireNonNull(licensed);
        this.policies = Objects.requireNonNull(policies);
        this.audit = Objects.requireNonNull(audit);
        this.clock = Objects.requireNonNull(clock);
    }

    /** The licence in force as the last reading has it; a licence that can't be read or is refused counts as none. */
    static LicensedSlots licensedSlots(PlatformLicence licence) {
        return () -> licence.status().map(status -> new LicensedSlots.Current(status.licence().tenantId(),
                status.licence().entitySlots().stream().map(EntitySlot::slotId).collect(Collectors.toSet()),
                switch (status.state()) {
                    case NOT_STARTED -> LicensedSlots.Standing.NOT_STARTED;
                    case EXPIRED_GRACE_OVER -> LicensedSlots.Standing.GRACE_OVER;
                    case VALID, EXPIRING, EXPIRED_IN_GRACE -> LicensedSlots.Standing.IN_FORCE;
                }));
    }

    @Transactional(readOnly = true)
    public Overview overview(AuthenticatedPrincipal principal, Optional<VerifiedSignIn> signIn) {
        Optional<String> customer = slots.customerOf(principal.tenantId());
        if (customer.isEmpty()) return new Overview(List.of());
        Set<String> managed = managed(customer.get(), principal, signIn);
        List<SlotView> out = new ArrayList<>();
        for (EntityRecord r : slots.slots(customer.get())) {
            boolean current = r.entityUuid().equals(principal.tenantId().value());
            if (!current && !managed.contains(r.slotId())) continue;
            var zone = slots.zones(TenantId.of(r.entityUuid()));
            out.add(new SlotView(r.slotId(), r.displayName(), r.status().name(), current,
                    zone.map(z -> z.own() == null ? null : z.own().getId()).orElse(null),
                    zone.map(z -> z.customer() == null ? null : z.customer().getId()).orElse(null), r.activatedAt()));
        }
        return new Overview(List.copyOf(out));
    }

    @Transactional
    public Answer activate(AuthenticatedPrincipal principal, Optional<VerifiedSignIn> signIn, CorrelationId correlation,
                           String slotId, String name) {
        EntityRecord before = requireManaged(principal, signIn, correlation, slotId, AuditAction.ENTITY_ACTIVATED);
        ActivationOutcome outcome = withActivation(a -> a.activate(slotId, name, actor(principal)));
        return finish(principal, correlation, before, AuditAction.ENTITY_ACTIVATED, outcome);
    }

    @Transactional
    public Answer rename(AuthenticatedPrincipal principal, Optional<VerifiedSignIn> signIn, CorrelationId correlation,
                         String slotId, String name) {
        EntityRecord before = requireManaged(principal, signIn, correlation, slotId, AuditAction.ENTITY_RENAMED);
        ActivationOutcome outcome = withActivation(a -> a.rename(slotId, name, actor(principal)));
        return finish(principal, correlation, before, AuditAction.ENTITY_RENAMED, outcome);
    }

    @Transactional
    public Answer suspend(AuthenticatedPrincipal principal, Optional<VerifiedSignIn> signIn, CorrelationId correlation,
                          String slotId) {
        EntityRecord before = requireManaged(principal, signIn, correlation, slotId, AuditAction.ENTITY_SUSPENDED);
        if (before.entityUuid().equals(principal.tenantId().value())) {
            return refused(principal, correlation, before, AuditAction.ENTITY_SUSPENDED, ENTITY_IN_USE_HERE,
                    "You are working in this entity.", "Switch to another entity, then suspend this one from there.");
        }
        ActivationOutcome outcome = withActivation(a -> a.suspend(slotId, actor(principal)));
        return finish(principal, correlation, before, AuditAction.ENTITY_SUSPENDED, outcome);
    }

    @Transactional
    public Answer resume(AuthenticatedPrincipal principal, Optional<VerifiedSignIn> signIn, CorrelationId correlation,
                         String slotId) {
        EntityRecord before = requireManaged(principal, signIn, correlation, slotId, AuditAction.ENTITY_RESUMED);
        ActivationOutcome outcome = withActivation(a -> a.resume(slotId, actor(principal)));
        return finish(principal, correlation, before, AuditAction.ENTITY_RESUMED, outcome);
    }

    /** Sets the entity's own time zone; a blank one clears it, and the entity follows the customer's again. */
    @Transactional
    public Answer setTimeZone(AuthenticatedPrincipal principal, Optional<VerifiedSignIn> signIn, CorrelationId correlation,
                              String slotId, String zoneName) {
        EntityRecord before = requireManaged(principal, signIn, correlation, slotId, AuditAction.ENTITY_TIME_ZONE_CHANGED);
        TimeZoneOutcome outcome = slots.withStore(s -> new EntityTimeZones(s).setEntityZone(slotId, zoneName, actor(principal)));
        // The zone name is a code from a fixed list; a refused one (typed text) is not recorded.
        String zone = outcome.refused() || outcome.zone() == null ? ""
                : ", timeZone=" + outcome.zone().effective().map(Object::toString).orElse("none");
        record(principal, correlation, before, AuditAction.ENTITY_TIME_ZONE_CHANGED, outcome.result(), outcome.refused(), zone);
        return answer(outcome.result(), outcome.refused());
    }

    /** Who an installation's activation is recorded for (a fixed id: no person, no sign-in). */
    static final ActorId INSTALL = ActorId.of(java.util.UUID.fromString("00000000-0000-0000-0000-000000000075"));

    /**
     * Task 0075: activates the customer's first entity at the installation, before anyone can sign in. Only while none was
     * ever activated; again with the same slot and name it changes nothing. Everything later is done in Admin, Entities.
     */
    @Transactional
    public Answer activateFirst(String slotId, String name, CorrelationId correlation) {
        List<String> customers = slots.customers();
        if (customers.size() != 1) {
            return new Answer(false, "NOT_ONE_CUSTOMER", "This database holds " + customers.size() + " customers.",
                    "Run setup first; it stores the customer and its entity slots.");
        }
        slots.lockCustomer(customers.get(0));
        List<EntityRecord> all = slots.slots(customers.get(0));
        Optional<EntityRecord> wanted = all.stream().filter(r -> r.slotId().equals(slotId)).findFirst();
        if (wanted.isEmpty()) {
            return new Answer(false, "NO_SUCH_SLOT", "The customer has no such entity slot.",
                    "Use one of: " + all.stream().map(EntityRecord::slotId).sorted().collect(Collectors.joining(", ")) + ".");
        }
        EntityRecord slot = wanted.get();
        if (all.stream().anyMatch(r -> r.activatedAt() != null)) {
            boolean same = slot.status() == com.documentplatform.identity.store.EntityStatus.ACTIVE && name != null
                    && slot.displayName().equals(name.strip()) && slots.hasEntityRow(slot.entityUuid());
            if (same) return new Answer(true, "UNCHANGED", "This entity is already active under this name.", null);
            return new Answer(false, "NOT_THE_FIRST", "An entity was activated before.",
                    "Use Admin, Entities as a Business Admin.");
        }
        ActivationOutcome outcome = withActivation(a -> a.activate(slotId, name, INSTALL.value().toString()));
        if (outcome.refused()) return answer(outcome.result(), true);
        TenantId entity = TenantId.of(outcome.entity().entityUuid());
        if (slots.mirror(outcome.entity(), clock.instant()) && policies.findByTenantId(entity).isEmpty()) {
            policies.save(TenantRedactionPolicy.defaults(entity));
        }
        audit.recordSuccess(entity, INSTALL, AuditAction.ENTITY_ACTIVATED, "EntitySlot", slotId, correlation,
                "code=" + outcome.result().code() + ", source=INSTALL");
        return answer(outcome.result(), false);
    }

    private ActivationOutcome withActivation(java.util.function.Function<SlotActivation, ActivationOutcome> work) {
        return slots.withStore((EntityStore s) -> work.apply(new SlotActivation(s, licensed, clock)));
    }

    /** LexaVault's own row follows the slot; then the record, then the answer. */
    private Answer finish(AuthenticatedPrincipal principal, CorrelationId correlation, EntityRecord before,
                          AuditAction action, ActivationOutcome outcome) {
        if (!outcome.refused() && outcome.entity() != null && slots.mirror(outcome.entity(), clock.instant())) {
            TenantId entity = TenantId.of(outcome.entity().entityUuid());
            if (policies.findByTenantId(entity).isEmpty()) policies.save(TenantRedactionPolicy.defaults(entity));
        }
        record(principal, correlation, before, action, outcome.result(), outcome.refused(), "");
        return answer(outcome.result(), outcome.refused());
    }

    private Answer refused(AuthenticatedPrincipal principal, CorrelationId correlation, EntityRecord slot,
                           AuditAction action, String code, String message, String whatToDo) {
        write(principal, correlation, slot, action, true, "code=" + code);
        return new Answer(false, code, message, whatToDo);
    }

    private static Answer answer(PlatformCode code, boolean refused) {
        return new Answer(!refused, code.code(), code.userMessage(), refused ? code.fix() : null);
    }

    private void record(AuthenticatedPrincipal principal, CorrelationId correlation, EntityRecord slot, AuditAction action,
                        PlatformCode code, boolean refused, String more) {
        write(principal, correlation, slot, action, refused, "code=" + code.code() + more);
    }

    private void write(AuthenticatedPrincipal principal, CorrelationId correlation, EntityRecord slot, AuditAction action,
                       boolean failed, String detail) {
        Set<TenantId> logs = new java.util.LinkedHashSet<>();
        logs.add(principal.tenantId());
        if (slots.hasEntityRow(slot.entityUuid())) logs.add(TenantId.of(slot.entityUuid()));
        for (TenantId log : logs) {
            if (failed) audit.recordFailure(log, principal.actorId(), action, "EntitySlot", slot.slotId(), correlation, detail);
            else audit.recordSuccess(log, principal.actorId(), action, "EntitySlot", slot.slotId(), correlation, detail);
        }
    }

    /** The slot, locked with its customer, when the person manages it; else refused, with a record of its own. */
    private EntityRecord requireManaged(AuthenticatedPrincipal principal, Optional<VerifiedSignIn> signIn,
                                        CorrelationId correlation, String slotId, AuditAction action) {
        Optional<String> customer = slots.customerOf(principal.tenantId());
        Supplier<AccessDeniedException> denied = () -> {
            audit.recordDeniedIndependently(principal.tenantId(), principal.actorId(), action, "EntitySlot", "refused",
                    correlation, "code=NOT_AN_ENTITY_YOU_MANAGE");
            return new AccessDeniedException(LvErrorCode.AUT_006, "You don't manage this entity");
        };
        if (customer.isEmpty()) throw denied.get();
        Set<String> managed = managed(customer.get(), principal, signIn);
        EntityRecord slot = slots.slots(customer.get()).stream().filter(r -> r.slotId().equals(slotId))
                .filter(r -> r.entityUuid().equals(principal.tenantId().value()) || managed.contains(r.slotId()))
                .findFirst().orElseThrow(denied);
        slots.lockCustomer(customer.get());
        return slots.slots(customer.get()).stream().filter(r -> r.slotId().equals(slot.slotId())).findFirst().orElseThrow(denied);
    }

    private Set<String> managed(String customer, AuthenticatedPrincipal principal, Optional<VerifiedSignIn> signIn) {
        // Without a verified sign-in (local development only) the person has the Business Admin role in name only: every
        // slot of the demo customer is shown, as every role is there.
        if (signIn.isEmpty()) {
            return slots.slots(customer).stream().map(EntityRecord::slotId).collect(Collectors.toSet());
        }
        return slots.slotsAdministeredBy(customer, signIn.get().issuer(), signIn.get().groups());
    }

    private static String actor(AuthenticatedPrincipal principal) {
        return principal.actorId().value().toString();
    }
}
