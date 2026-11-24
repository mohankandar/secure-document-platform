package com.securedoc.platform.api.controller;

import com.documentplatform.licensing.Allowance;
import com.documentplatform.licensing.AllowanceType;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.Licence;
import com.documentplatform.licensing.LicenceStatus;
import com.documentplatform.licensing.usage.UsageLedger;
import com.documentplatform.licensing.usage.UsageLevel;
import com.securedoc.platform.api.context.RequestContextResolver;
import com.securedoc.platform.api.licensing.PlatformLicence;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.identity.LexaVaultProduct;
import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * Task 0057: the Licensing screen's read (Business Admin): the licence in force, whether intake is allowed, and this month's
 * use of the caller's own entity. Nothing can be changed here: a licence is stored with the licence command. Never a key, a
 * signature or a price.
 */
@RestController
@RequestMapping("/api/v1/licensing")
public class LicensingController {

    private final PlatformLicence licence;
    private final UsageLedger ledger;
    private final RequestContextResolver context;

    public LicensingController(PlatformLicence licence, UsageLedger ledger, RequestContextResolver context) {
        this.licence = licence;
        this.ledger = ledger;
        this.context = context;
    }

    @GetMapping
    @RequireCapability(UserCapability.TENANT_POLICY_MANAGE)
    public ResponseEntity<LicensingResponse> licence(HttpServletRequest request) {
        TenantId entity = context.resolvePrincipal(request).tenantId();
        Optional<LicenseEnforcementException> refusal = licence.refusal(entity);
        StoppedIntake stopped = refusal.map(r -> {
            LvErrorCode code = LvErrorCodes.of(r);
            return new StoppedIntake(code.code(), code.title(), r.platformCode().orElse(null));
        }).orElse(null);
        return ResponseEntity.ok(new LicensingResponse(refusal.isEmpty(), stopped,
                licence.status().map(LicensingController::licenceOf).orElse(null),
                licence.status().map(s -> entityOf(s, entity)).orElse(null)));
    }

    private static LicenceView licenceOf(LicenceStatus s) {
        Licence l = s.licence();
        return new LicenceView(l.licenceId(), l.version(), l.customer(), l.type().name(), l.kind().name(),
                l.kind().testEnvironment(), s.state().name(), s.message(), l.startsOn(), l.expiresOn(), l.updatesUntil(),
                s.daysUntilExpiry().isPresent() ? s.daysUntilExpiry().getAsLong() : null, s.graceEndsOn().orElse(null),
                s.clock().message().orElse(null), s.waiting() == null ? null
                        : new WaitingLicence(s.waiting().licenceId(), s.waiting().version(), s.waiting().startsOn()));
    }

    private EntityView entityOf(LicenceStatus s, TenantId entity) {
        Optional<EntitySlot> slot = licence.slot(entity);
        if (slot.isEmpty()) return null;
        YearMonth month = YearMonth.from(s.clock().effective().atZone(ZoneOffset.UTC));
        List<AllowanceView> allowances = new ArrayList<>();
        for (Allowance a : slot.get().allowances()) {
            if (!LexaVaultProduct.CODE.equals(a.product())) continue;
            allowances.add(allowance(slot.get().slotId(), a.type(), a.amount(), month));
        }
        // Pages are always counted; without an allowance they are shown as over the limit, as platform-licensing has it.
        if (allowances.stream().noneMatch(a -> a.type().equals(AllowanceType.PAGES_PER_MONTH.name()))) {
            allowances.add(allowance(slot.get().slotId(), AllowanceType.PAGES_PER_MONTH, 0, month));
        }
        return new EntityView(slot.get().slotId(), month.toString(), allowances);
    }

    private AllowanceView allowance(String slotId, AllowanceType type, long limit, YearMonth month) {
        long used = ledger.usage(slotId, LexaVaultProduct.CODE, type, month);
        return new AllowanceView(type.name(), type.unit(), used, limit, UsageLevel.of(used, limit).name());
    }

    /** {@code licence} and {@code entity} are empty when the licence can't be read or the entity is not in it. */
    public record LicensingResponse(boolean intakeAllowed, StoppedIntake stopped, LicenceView licence, EntityView entity) {
    }

    /** Why intake is stopped: LexaVault's code, and the licensing library's own code or state. */
    public record StoppedIntake(String code, String title, String platformCode) {
    }

    public record LicenceView(String licenceId, long version, String customer, String type, String kind,
                              boolean testEnvironment, String state, String message, LocalDate startsOn,
                              LocalDate expiresOn, LocalDate updatesUntil, Long daysUntilExpiry, LocalDate graceEndsOn,
                              String clockMessage, WaitingLicence waiting) {
    }

    public record WaitingLicence(String licenceId, long version, LocalDate startsOn) {
    }

    public record EntityView(String slotId, String month, List<AllowanceView> allowances) {
    }

    public record AllowanceView(String type, String unit, long used, long limit, String level) {
    }
}
