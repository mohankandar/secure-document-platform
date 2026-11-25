package com.securedoc.platform.infrastructure.delivery;

import com.documentplatform.fields.FieldSet;
import com.documentplatform.integration.IntegrationCode;
import com.documentplatform.integration.config.CallStep;
import com.documentplatform.integration.config.IntegrationConfig;
import com.documentplatform.integration.config.SignIn;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.integration.engine.CaseFields;
import com.documentplatform.integration.engine.Delivery;
import com.documentplatform.integration.engine.TestAction;
import com.documentplatform.integration.profile.IntegrationProfile;
import com.documentplatform.integration.result.AttemptResult;
import com.documentplatform.integration.result.Outcome;
import com.documentplatform.integration.result.PendingStatus;
import com.documentplatform.integration.store.IntegrationAudit;
import com.documentplatform.integration.store.StoredIntegration;
import com.securedoc.platform.application.exception.TechnicalDependency;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.pipeline.ExportJobAttempt;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.DocumentStoreConnectionTestPort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.domain.document.SensitivityLevel;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.DestinationSettings;
import com.securedoc.platform.domain.integration.IntegrationConnection;
import com.securedoc.platform.domain.integration.IntegrationPurpose;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Task 0087: delivery of a case to a destination, described by the profile its owner publishes and run by
 * platform-integration. LexaVault keeps the workflow (export job, retries, pauses, IT's queue); this turns one attempt
 * into the profile's calls and the calls' outcome into an LV code.
 *
 * <p><b>Nothing twice.</b> The package and document ids are the submission's own and never change, the documents go in a
 * fixed order, and after every call that worked the progress is saved, so the next attempt (on any server) goes on from
 * there. A call whose answer was lost is made again with the same ids; the destination's "already received" counts as
 * exported (LV-DLV-009). Progress that does not belong to this connection, address and profile, and a delivery an older
 * release left half done, are not sent on: LV-DLV-016.
 *
 * <p>The profile decides what can be said: a sensitivity level it has no place for is never dropped silently
 * (LV-DLV-003). Documents are streamed from their stored copy; nothing is written to disk and no value is logged.
 */
public final class ProfileDestinationDeliveryAdapter implements DeliveryPort, DocumentStoreConnectionTestPort {

    static final String DESTINATION_TYPE = "document-store";
    static final String HALF_DONE = "DELIVERY_OF_AN_EARLIER_SETUP";
    private static final int MAX_REFERENCE = 255;
    private static final Duration MAX_WAIT = Duration.ofHours(1);

    private final IntegrationConnectionRepository connections;
    private final DestinationProfiles profiles;
    private final CallEngine engine;
    private final boolean singleAttempt;
    private final Clock clock;

    /** @param singleAttempt export jobs are on: one attempt for each call, the job spaces the tries */
    public ProfileDestinationDeliveryAdapter(IntegrationConnectionRepository connections, DestinationProfiles profiles,
                                             CallEngine engine, boolean singleAttempt, Clock clock) {
        this.connections = Objects.requireNonNull(connections);
        this.profiles = Objects.requireNonNull(profiles);
        this.engine = Objects.requireNonNull(engine);
        this.singleAttempt = singleAttempt;
        this.clock = Objects.requireNonNull(clock);
    }

    @Override
    public DeliveryDescriptor describe(TenantId tenantId) {
        IntegrationConnection c = connection(tenantId);
        return new DeliveryDescriptor(DESTINATION_TYPE, c.id(), c.configurationVersion());
    }

    // ---------------------------------------------------------------- test and health

    /** Health and sign-in only: no submission and no document is created, whatever the profile's own test would do. */
    @Override
    public void testConnection(IntegrationConnection c) {
        if (c == null || !c.enabled() || c.purpose() != IntegrationPurpose.DOCUMENT_STORE_DELIVERY) {
            throw failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false,
                    "Integration connection is not an enabled delivery destination", null);
        }
        DestinationProfiles.Resolved r;
        try {
            r = profiles.resolve(c);
        } catch (DestinationProfiles.NotUsable e) {
            throw notUsable(e);
        }
        AttemptResult result = new TestAction(engine, IntegrationAudit.NONE).run(stored(r.config(), c), "lexavault");
        // Busy fails the check too, as a 503 always did: a case that could not be sent then waits without using an attempt.
        if (result.delivered()) return;
        throw failed(result, c.destination(), "Destination connection test failed");
    }

    @Override
    public void checkDestination(TenantId tenantId) {
        testConnection(connection(tenantId));
    }

    // ---------------------------------------------------------------- delivery

    @Override
    public BatchDeliveryReference deliverBatch(DeliveryBatch batch) {
        IntegrationConnection c = connection(batch.tenantId());
        try {
            Run run = new Run(c, batch);
            int attempts = singleAttempt || ExportJobAttempt.running() ? 1 : Math.max(1, c.retryMaxAttempts() + 1);
            for (int i = 1; ; i++) {
                try {
                    BatchDeliveryReference done = run.once();
                    connections.save(c.recordOperationalSuccess(clock.instant()));
                    return done;
                } catch (SignInAgain refusedToken) {
                    i--; // once: the held token was dropped, and the next call gets a new one
                } catch (TechnicalDependencyException e) {
                    if (!e.retryable() || i >= attempts) throw e.withAttempts(i);
                    try {
                        Thread.sleep(Math.min(1000L * i, 3000L));
                    } catch (InterruptedException interrupted) {
                        Thread.currentThread().interrupt();
                        throw e.withAttempts(i);
                    }
                }
            }
        } catch (TechnicalDependencyException e) {
            connections.save(c.recordOperationalFailure(e.failureCode().name(), clock.instant()));
            throw e;
        }
    }

    /** A held token was refused; the engine dropped it. */
    private static final class SignInAgain extends RuntimeException {
        SignInAgain() {
            super("new token", null, false, false);
        }
    }

    /** One delivery, over the inline tries of one call. */
    private final class Run {
        private final IntegrationConnection c;
        private final DeliveryBatch batch;
        private final IntegrationConfig config;
        private final IntegrationProfile profile;
        private final List<DeliveryItem> pending = new ArrayList<>();
        private final Delivery delivery;
        private final Map<String, DeliveryReference> references = new LinkedHashMap<>();
        private final String key;
        private DeliveryProgress progress;
        private final boolean submissionKnown;
        private boolean newTokenAsked;

        Run(IntegrationConnection c, DeliveryBatch batch) {
            this.c = c;
            this.batch = batch;
            progress = DeliveryProgress.parse(batch.context().progress());
            if (progress == null && (batch.existingDestinationSubmissionReference() != null || !batch.context().acknowledged().isEmpty())) {
                throw halfDone("The delivery was begun by an earlier release and can't be continued safely");
            }
            CaseFields fields = fields();
            DestinationProfiles.Resolved r;
            try {
                FieldSet list = batch.context().fieldList();
                r = profiles.resolve(c, field -> fields.field(field).isPresent(),
                        list == null ? List.of() : list.active().stream().map(spec -> spec.key().value()).toList());
            } catch (DestinationProfiles.NotUsable e) {
                throw notUsable(e);
            }
            config = r.config();
            profile = r.profile();
            key = c.id().value() + "|" + sha(c.endpoint()) + "|" + profile.name();
            if (progress != null && !progress.key().equals(key)) {
                throw halfDone("The delivery was begun with another connection, address or profile");
            }
            submissionKnown = batch.existingDestinationSubmissionReference() != null;
            if (progress == null) progress = DeliveryProgress.begun(key);
            SensitivityLevel caseLevel = batch.caseSensitivityLevel();
            List<Delivery.Document> documents = new ArrayList<>();
            Set<String> acknowledged = new LinkedHashSet<>();
            batch.context().acknowledged().forEach(item -> acknowledged.add(item.sourceDocumentId()));
            List<DeliveryItem> all = new ArrayList<>(batch.context().acknowledged());
            all.addAll(batch.items());
            // One order for every attempt, whatever order the batch has: a repeated call is the same call.
            all.sort(Comparator.comparing(DeliveryItem::sourceDocumentId));
            for (DeliveryItem item : all) {
                documents.add(document(item, caseLevel));
                if (!acknowledged.contains(item.sourceDocumentId())) pending.add(item);
            }
            Map<String, String> values = new LinkedHashMap<>();
            values.put(DestinationProfiles.REVISION_VALUE, Integer.toString(batch.exportCycle()));
            values.put(DestinationProfiles.CASE_LEVEL_VALUE, caseLevel.name());
            try {
                delivery = profile.prepare(new Delivery(batch.sourceSubmissionId(), batch.sourceSubmissionId(), documents, values,
                        Delivery.Resume.NONE, fields));
            } catch (IllegalArgumentException e) {
                // The ids, names and levels were checked above; what is left is a level the profile does not list.
                throw level("The destination's profile does not take a document's sensitivity level");
            }
        }

        /** The case's fields, typed. Values that can't be read as typed are not left out: nothing is sent. */
        private CaseFields fields() {
            if (batch.caseFields() != null && batch.context().fieldList() != null) {
                try {
                    return CaseFields.fromDocument(batch.caseFields(), batch.context().fieldList());
                } catch (CaseFields.Invalid e) {
                    throw setUp(IntegrationCode.INT_FIELD_VALUES_INVALID.name(), "The case's fields can't be sent as they are stored");
                }
            }
            DestinationSettings d = c.destination();
            boolean wanted = d != null && (d.sendAllFields() || !d.fieldMapping().isEmpty());
            if (wanted && !batch.businessIdentifiers().isEmpty()) {
                throw setUp(IntegrationCode.INT_FIELD_VALUES_INVALID.name(), "The case's fields can't be sent as they are stored");
            }
            return CaseFields.NONE;
        }

        private Delivery.Document document(DeliveryItem item, SensitivityLevel caseLevel) {
            if (!"SHA-256".equalsIgnoreCase(item.checksumAlgorithm()) || item.checksumValue() == null) {
                throw new TechnicalDependencyException(TechnicalDependency.DELIVERY, TechnicalFailureCode.DELIVERY_IO, false,
                        "A redacted copy has no SHA-256 checksum").withLvCode(LvErrorCode.DLV_011);
            }
            boolean carriesLevel = DestinationProfiles.carriesLevel(profile);
            SensitivityLevel level = item.sensitivityLevel();
            if (!DestinationProfiles.carriesCaseLevel(profile) && caseLevel != SensitivityLevel.STANDARD) {
                if (c.destination().caseLevel() != DestinationSettings.CaseLevel.ON_EACH_DOCUMENT) {
                    throw level("The destination's profile has no place for the case's sensitivity level");
                }
                if (caseLevel.compareTo(level) > 0) level = caseLevel;
            }
            if (!carriesLevel && level != SensitivityLevel.STANDARD) {
                throw level("The destination's profile has no place for a document's sensitivity level");
            }
            try {
                return new Delivery.Document(item.sourceDocumentId(), fileName(item.originalFileName()), item.mimeType(),
                        item.sizeBytes(), item.checksumValue().toLowerCase(Locale.ROOT), item.contentSource()::open,
                        carriesLevel ? Map.of(DestinationProfiles.LEVEL_ATTRIBUTE, level.name()) : Map.of());
            } catch (IllegalArgumentException e) {
                throw setUp(IntegrationCode.INT_VALUE_NOT_ALLOWED.name(), "A document's id, name or type can't be sent");
            }
        }

        BatchDeliveryReference once() {
            List<CallStep> steps = config.steps();
            int first = 0;
            while (first < steps.size() && steps.get(first).repeat() == CallStep.Repeat.ONCE) first++;
            int end = first;
            while (end < steps.size() && steps.get(end).repeat() == CallStep.Repeat.EACH_DOCUMENT) end++;
            boolean perDocument = end > first;
            save();
            if (progress.pendingReference() != null) {
                done(engine.checkStatus(stored(config, c), new PendingStatus(config.id(), c.configurationVersion(),
                        progress.pendingStep(), progress.pendingReference(), clock.instant(), progress.correlationId(),
                        progress.values())));
            } else {
                for (int i = 0; i < first; i++) step(steps, i, !perDocument);
                for (DeliveryItem item : List.copyOf(pending)) {
                    if (!perDocument || references.containsKey(item.sourceDocumentId())) continue;
                    Set<String> others = new LinkedHashSet<>();
                    delivery.documents().forEach(d -> others.add(d.sourceDocumentId()));
                    others.remove(item.sourceDocumentId());
                    AttemptResult r = engine.attempt(stored(config.withSteps(steps.subList(0, end)), c),
                            delivery.resumingFrom(new Delivery.Resume(stepNames(steps, first), progress.values(), others)));
                    done(r);
                    if (r.steps().stream().anyMatch(s -> s.outcome() == Outcome.ALREADY_RECEIVED)) {
                        progress = progress.alreadyReceived();
                        save();
                    }
                    acknowledge(item, reference(steps.subList(first, end), r.documentValues().getOrDefault(item.sourceDocumentId(), Map.of())));
                }
                for (int i = end; i < steps.size(); i++) step(steps, i, false);
            }
            if (!perDocument) {
                for (DeliveryItem item : List.copyOf(pending)) {
                    if (!references.containsKey(item.sourceDocumentId())) acknowledge(item, null);
                }
            }
            if (references.size() != pending.size()) {
                throw failure(TechnicalFailureCode.DELIVERY_IO, true, "The destination has not confirmed every document", null)
                        .withLvCode(LvErrorCode.DLV_008);
            }
            String reference = submissionReference();
            return new BatchDeliveryReference(DESTINATION_TYPE, reference, List.copyOf(references.values()), progress.already());
        }

        /** One call made once for the case, unless an earlier attempt made it. Its progress is saved before the next call. */
        private void step(List<CallStep> steps, int index, boolean carriesTheCase) {
            CallStep s = steps.get(index);
            if (progress.steps().contains(s.name())) return;
            Set<String> all = new LinkedHashSet<>();
            delivery.documents().forEach(d -> all.add(d.sourceDocumentId()));
            AttemptResult r = engine.attempt(stored(config.withSteps(steps.subList(0, index + 1)), c),
                    delivery.resumingFrom(new Delivery.Resume(progress.steps(), progress.values(), all)));
            done(r);
            progress = progress.after(s.name(), r.values());
            if (carriesTheCase && r.outcome() == Outcome.ALREADY_RECEIVED) progress = progress.alreadyReceived();
            save();
            String reference = submissionReference();
            if (reference != null && !submissionKnown) batch.progressListener().submissionAccepted(reference);
        }

        /** Goes on after SENT or ALREADY_RECEIVED; "accepted, check later" and every failure end the attempt here. */
        private void done(AttemptResult r) {
            if (r.delivered()) return;
            if (r.outcome() == Outcome.SIGN_IN_FAILED && r.httpStatus() == 401 && !newTokenAsked
                    && config.signIn() instanceof SignIn.OAuth2ClientCredentials) {
                newTokenAsked = true;
                throw new SignInAgain();
            }
            if (r.outcome() == Outcome.ACCEPTED) {
                progress = progress.accepted(r.pending().step(), r.pending().reference(), r.pending().correlationId(), r.values());
                save();
                // Not a failure and not done: the job waits and asks again, using no attempt.
                throw failure(TechnicalFailureCode.DELIVERY_IO, true, "The destination is still working on the delivery", null)
                        .withLvCode(LvErrorCode.DLV_008).asRateLimited(waitFor(Duration.between(clock.instant(), r.pending().nextCheckAt())));
            }
            throw failed(r, c.destination(), "Delivery to the destination failed at step " + r.stepReached());
        }

        private void acknowledge(DeliveryItem item, String documentReference) {
            String submission = submissionReference();
            DeliveryReference ref = new DeliveryReference(item.documentId(), item.sourceDocumentId(), DESTINATION_TYPE,
                    documentReference, "destination:" + (submission == null ? batch.sourceSubmissionId() : submission) + "/"
                    + (documentReference == null ? item.sourceDocumentId() : documentReference));
            references.put(item.sourceDocumentId(), ref);
            batch.progressListener().documentAcknowledged(ref);
        }

        /** The destination's own id of the delivery: what its first call must answer with; null when none. */
        private String submissionReference() {
            if (batch.existingDestinationSubmissionReference() != null) return batch.existingDestinationSubmissionReference();
            for (CallStep s : config.steps()) {
                if (s.repeat() != CallStep.Repeat.ONCE) break;
                String v = identifier(s, progress.values());
                if (v != null) return v;
            }
            return null;
        }

        private String reference(List<CallStep> block, Map<String, String> values) {
            for (CallStep s : block) {
                String v = identifier(s, values);
                if (v != null) return v;
            }
            return null;
        }

        /** The value the answer must have, other than the checksum and size; the first by name when there are several. */
        private String identifier(CallStep s, Map<String, String> values) {
            for (String name : new TreeSet<>(s.required())) {
                if (s.integrity() != null && (name.equals(s.integrity().sha256Value()) || name.equals(s.integrity().sizeValue()))) continue;
                String v = values.get(s.name() + "." + name);
                if (v != null) return cut(v);
            }
            return null;
        }

        private Set<String> stepNames(List<CallStep> steps, int end) {
            Set<String> out = new LinkedHashSet<>();
            steps.subList(0, end).forEach(s -> out.add(s.name()));
            return out;
        }

        private void save() {
            batch.progressListener().progressed(progress.write());
        }
    }

    // ---------------------------------------------------------------- helpers

    private IntegrationConnection connection(TenantId tenantId) {
        return connections.findEnabledDocumentStoreByTenantId(tenantId)
                .filter(IntegrationConnection::enabled)
                .orElseThrow(() -> failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false,
                        "No enabled delivery destination is configured for this entity", null).withLvCode(LvErrorCode.DLV_006));
    }

    private static StoredIntegration stored(IntegrationConfig config, IntegrationConnection c) {
        return new StoredIntegration(config, c.configurationVersion(), "lexavault", c.updatedAt());
    }

    /** The platform's outcome as an LV code and what the export job does with it. The platform's own code is the detail. */
    static TechnicalDependencyException failed(AttemptResult r, DestinationSettings settings, String message) {
        String detail = r.destinationCode() != null ? r.destinationCode() : r.code().name();
        return switch (r.outcome()) {
            case BUSY -> failure(TechnicalFailureCode.DELIVERY_IO, true, message, null).withLvCode(LvErrorCode.DLV_002)
                    .withDestinationCode(r.destinationCode()).asRateLimited(waitFor(r.retryAfter()));
            case OUTAGE -> failure(TechnicalFailureCode.DELIVERY_IO, true, message, null).withLvCode(LvErrorCode.DLV_002)
                    .withDestinationCode(detail);
            case CANT_CONNECT -> failure(TechnicalFailureCode.DELIVERY_IO, true, message, null).withLvCode(LvErrorCode.DLV_001)
                    .withDestinationCode(detail);
            case SIGN_IN_FAILED -> failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, message, null)
                    .withLvCode(LvErrorCode.DLV_005).withDestinationCode(detail);
            case REFUSED -> settings != null && settings.refusesLevel(r.destinationCode())
                    ? failure(TechnicalFailureCode.DELIVERY_SENSITIVITY_REFUSED, false, message, null)
                            .withDestinationCode(r.destinationCode())
                    : failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, message, null)
                            .withLvCode(LvErrorCode.DLV_004).withDestinationCode(detail);
            case NOT_CONFIGURED -> failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, message, null)
                    .withLvCode(r.code() == IntegrationCode.INT_SECRET_NOT_FOUND || r.code() == IntegrationCode.INT_CONFIG_SECRET_SCOPE
                            ? LvErrorCode.DLV_005 : LvErrorCode.DLV_006).withDestinationCode(detail);
            case UNCLEAR, SENT, ALREADY_RECEIVED, ACCEPTED -> failure(TechnicalFailureCode.DELIVERY_IO, true, message, null)
                    .withLvCode(LvErrorCode.DLV_007).withDestinationCode(detail);
        };
    }

    private static TechnicalDependencyException notUsable(DestinationProfiles.NotUsable e) {
        LvErrorCode code = e.code().equals(IntegrationCode.INT_CONFIG_SECRET_SCOPE.name()) ? LvErrorCode.DLV_005 : LvErrorCode.DLV_006;
        return failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, e.getMessage(), null).withLvCode(code)
                .withDestinationCode(e.code());
    }

    private static TechnicalDependencyException setUp(String code, String message) {
        return failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, message, null).withLvCode(LvErrorCode.DLV_006)
                .withDestinationCode(code);
    }

    private static TechnicalDependencyException level(String message) {
        return failure(TechnicalFailureCode.DELIVERY_SENSITIVITY_REFUSED, false, message, null);
    }

    private static TechnicalDependencyException halfDone(String message) {
        return failure(TechnicalFailureCode.DELIVERY_DESTINATION_INVALID, false, message, null).withLvCode(LvErrorCode.DLV_016)
                .withDestinationCode(HALF_DONE);
    }

    private static TechnicalDependencyException failure(TechnicalFailureCode code, boolean retryable, String message, Throwable cause) {
        return new TechnicalDependencyException(TechnicalDependency.DELIVERY, code, retryable, message, cause);
    }

    /** A destination's wait: null (the job's own) when it gave none, at most one hour. */
    private static Duration waitFor(Duration asked) {
        if (asked == null || asked.isNegative() || asked.isZero()) return null;
        return asked.compareTo(MAX_WAIT) > 0 ? MAX_WAIT : asked;
    }

    /** A plain file name: no path, no control characters, at most 500 characters. */
    static String fileName(String name) {
        StringBuilder out = new StringBuilder();
        (name == null || name.isBlank() ? "document.pdf" : name).chars().limit(500)
                .forEach(ch -> out.append(ch < 0x20 || ch == 0x7f || ch == '/' || ch == '\\' ? '_' : (char) ch));
        return out.toString();
    }

    private static String cut(String v) {
        return v.length() <= MAX_REFERENCE ? v : v.substring(0, MAX_REFERENCE);
    }

    private static String sha(String v) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(v.getBytes(StandardCharsets.UTF_8))).substring(0, 32);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
