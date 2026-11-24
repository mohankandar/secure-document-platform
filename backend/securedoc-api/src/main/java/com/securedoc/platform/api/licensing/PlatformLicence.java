package com.securedoc.platform.api.licensing;

import com.documentplatform.identity.EntityUuid;
import com.documentplatform.licensing.EntitySlot;
import com.documentplatform.licensing.LicenceException;
import com.documentplatform.licensing.LicenceRefusal;
import com.documentplatform.licensing.LicenceState;
import com.documentplatform.licensing.LicenceStatus;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.licensing.IntakeLicence;
import com.securedoc.platform.application.licensing.LicenseEnforcementException;
import com.securedoc.platform.application.service.AuditService;
import com.securedoc.platform.domain.audit.AuditAction;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.CorrelationId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.licensing.LicenseValidationStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.LongSupplier;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Task 0057: the stored licence as LexaVault uses it: whether new work is taken in, whether it is a licence for tests, and
 * what the Licensing screen shows. The rules are platform-licensing's; this only asks.
 *
 * <p>Reading the licence uses the database, and the questions come from inside requests that already hold a connection. So
 * one thread of its own reads it every {@link #EVERY} and every question is answered from the last reading: a renewal or an
 * expiry counts within that time. A reading older than {@link #MAX_AGE} (the database can't be reached) counts as no licence
 * (fail closed), and so does a licence that is refused.
 */
public class PlatformLicence implements IntakeLicence, AutoCloseable {

    static final Duration EVERY = Duration.ofSeconds(10);
    static final Duration MAX_AGE = Duration.ofMinutes(2);

    private static final Logger log = LoggerFactory.getLogger(PlatformLicence.class);

    /**
     * One reading: the licence in force ({@code status}), or why it is refused. {@code productLicensed} is whether it lists
     * LexaVault.
     */
    public record Reading(LicenceStatus status, LicenceRefusal refusal, boolean productLicensed, long atNanos) {
    }

    private final Supplier<LicenceStatus> licence;
    private final Predicate<LicenceStatus> productLicensed;
    private final AuditService audit;
    private final LongSupplier nanos;
    private volatile Reading last;
    private volatile String lastState;
    private Runnable afterReading = () -> { };
    private ScheduledExecutorService reader;

    /**
     * @param licence         the stored licence's status now ({@code LicenceService::status})
     * @param productLicensed whether it lists LexaVault ({@code LicenceService::productLicensed})
     */
    public PlatformLicence(Supplier<LicenceStatus> licence, Predicate<LicenceStatus> productLicensed, AuditService audit) {
        this(licence, productLicensed, audit, System::nanoTime);
    }

    PlatformLicence(Supplier<LicenceStatus> licence, Predicate<LicenceStatus> productLicensed, AuditService audit,
                    LongSupplier nanos) {
        this.licence = Objects.requireNonNull(licence);
        this.productLicensed = Objects.requireNonNull(productLicensed);
        this.audit = Objects.requireNonNull(audit);
        this.nanos = nanos;
        read();
    }

    /** Starts the readings. {@code afterEachReading} puts in the pages that still wait. */
    public synchronized void start(Runnable afterEachReading) {
        if (reader != null) return;
        this.afterReading = Objects.requireNonNull(afterEachReading);
        reader = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "licence-reader");
            t.setDaemon(true);
            return t;
        });
        reader.scheduleWithFixedDelay(this::round, EVERY.toMillis(), EVERY.toMillis(), TimeUnit.MILLISECONDS);
    }

    @Override
    public synchronized void close() {
        if (reader != null) reader.shutdownNow();
        reader = null;
    }

    private void round() {
        read();
        try {
            afterReading.run();
        } catch (RuntimeException e) {
            log.warn("LICENCE_USAGE_ROUND_FAILED type={}", e.getClass().getSimpleName());
        }
    }

    /** Reads the licence now. While the database can't be reached the last reading stays until it is too old. */
    public final void read() {
        try {
            LicenceStatus status = licence.get();
            last = new Reading(status, null, productLicensed.test(status), nanos.getAsLong());
        } catch (LicenceException refused) {
            last = new Reading(null, refused.code(), false, nanos.getAsLong());
        } catch (RuntimeException unreadable) {
            log.warn("LICENCE_READ_FAILED type={}", unreadable.getClass().getSimpleName());
        }
        noteState();
    }

    /** The last reading; empty when there is none or it is too old to trust. */
    public Optional<Reading> reading() {
        Reading r = last;
        return r == null || nanos.getAsLong() - r.atNanos() > MAX_AGE.toNanos() ? Optional.empty() : Optional.of(r);
    }

    /** The licence in force, if it can be read now and is not refused. */
    public Optional<LicenceStatus> status() {
        return reading().map(Reading::status);
    }

    /** The entity's slot in the licence in force. */
    public Optional<EntitySlot> slot(TenantId tenantId) {
        return status().flatMap(s -> s.licence().entitySlots().stream()
                .filter(slot -> EntityUuid.fromSlotId(slot.slotId()).equals(tenantId.value())).findFirst());
    }

    @Override
    public Optional<LicenseEnforcementException> refusal(TenantId tenantId) {
        Optional<Reading> reading = reading();
        if (reading.isEmpty()) {
            return refused("LICENSE_CONFIGURATION_ERROR", LicenseValidationStatus.CONFIGURATION_ERROR,
                    "LICENCE_NOT_READABLE", "The licence can't be read now");
        }
        Reading r = reading.get();
        if (r.refusal() != null) {
            return switch (r.refusal()) {
                case LICENCE_MISSING -> refused("LICENSE_REQUIRED", LicenseValidationStatus.MISSING, r.refusal().name(),
                        "No licence is installed");
                case LICENCE_WRONG_CUSTOMER -> refused("LICENSE_TENANT_MISMATCH", LicenseValidationStatus.TENANT_MISMATCH,
                        r.refusal().name(), "The licence is for another customer");
                default -> refused("LICENSE_INVALID_SIGNATURE", LicenseValidationStatus.INVALID_SIGNATURE,
                        r.refusal().name(), "The licence is not valid");
            };
        }
        if (!r.status().intakeAllowed()) {
            boolean notStarted = r.status().state() == LicenceState.NOT_STARTED;
            return refused(notStarted ? "LICENSE_NOT_YET_VALID" : "LICENSE_EXPIRED",
                    notStarted ? LicenseValidationStatus.NOT_YET_VALID : LicenseValidationStatus.EXPIRED,
                    r.status().state().name(), "Intake is stopped by the licence");
        }
        if (!r.productLicensed()) {
            return refused("LICENSE_NOT_PERMITTED", LicenseValidationStatus.ACTIVE, "PRODUCT_NOT_IN_LICENCE",
                    "The licence does not cover LexaVault");
        }
        if (tenantId == null || slot(tenantId).isEmpty()) {
            return refused("LICENSE_TENANT_MISMATCH", LicenseValidationStatus.TENANT_MISMATCH, "ENTITY_NOT_IN_LICENCE",
                    "The entity is not in the licence");
        }
        return Optional.empty();
    }

    private static Optional<LicenseEnforcementException> refused(String errorCode, LicenseValidationStatus status,
                                                                 String detail, String message) {
        return Optional.of(new LicenseEnforcementException(errorCode, message, status, detail));
    }

    @Override
    public void requireIntake(TenantId tenantId, ActorId actorId, CorrelationId correlationId) {
        Optional<LicenseEnforcementException> refusal = refusal(tenantId);
        if (refusal.isEmpty()) return;
        LicenseEnforcementException refused = refusal.get();
        // Task 0023 C6: its own transaction, so the record survives the refused request's rollback.
        audit.recordDeniedIndependently(tenantId, actorId, AuditAction.LICENSE_ENFORCEMENT_DENIED, "Tenant",
                tenantId.toString(), correlationId, "Intake stopped by the licence: " + LvErrorCodes.auditDetail(
                        LvErrorCodes.of(refused), refused.errorCode(), "status=" + refused.status() + ", reason="
                                + refused.platformCode().orElse("none")));
        throw refused;
    }

    @Override
    public Optional<String> customerName() {
        return status().map(s -> s.licence().customer().strip()).filter(name -> !name.isBlank());
    }

    /** One log line when the licence's state changes, not one per reading. */
    private void noteState() {
        Optional<Reading> now = reading();
        boolean allowed = now.isPresent() && now.get().refusal() == null && now.get().status().intakeAllowed()
                && now.get().productLicensed();
        String state = now.map(r -> r.refusal() != null ? r.refusal().name()
                : r.status().state().name() + "/" + r.status().licence().kind()).orElse("LICENCE_NOT_READABLE")
                + " intakeAllowed=" + allowed;
        if (state.equals(lastState)) return;
        lastState = state;
        if (allowed) log.info("LICENCE_STATE state={}", state);
        else log.warn("LICENCE_STATE state={}", state);
    }
}
