package com.securedoc.platform.infrastructure.delivery;

import com.documentplatform.fields.delivery.FieldDelivery;
import com.documentplatform.fields.FieldSet;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.FieldValue;
import com.documentplatform.integration.engine.CallEngine;
import com.documentplatform.notifications.secret.Secret;
import com.documentplatform.notifications.secret.SecretRef;
import com.documentplatform.outbound.Installation;
import com.documentplatform.outbound.Outbound;
import com.securedoc.platform.application.error.LvErrorCodes;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.pipeline.ExportJobAttempt;
import com.securedoc.platform.application.port.out.DeliveryPort;
import com.securedoc.platform.application.port.out.IntegrationConnectionRepository;
import com.securedoc.platform.domain.identity.DocumentId;
import com.securedoc.platform.domain.document.SensitivityLevel;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.identity.IntegrationConnectionId;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.domain.integration.*;
import com.securedoc.platform.domain.identity.PackageId;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

/** Task 0087: one delivery through a published profile, against a made-up archive that stores things once. */
class ProfileDestinationDeliveryTest {
    private static final String SECRET = "a-made-up-client-secret";
    private final TenantId tenant = TenantId.generate();
    private final FieldSet fieldSet = FieldSet.of(List.of(
            FieldSpec.builder("contract_number", "Contract number", FieldType.TEXT).build(),
            FieldSpec.builder("amount", "Amount", FieldType.DECIMAL).build(),
            FieldSpec.builder("opened_on", "Opened on", FieldType.DATE).build(),
            FieldSpec.builder("member_ref", "Member ref", FieldType.TEXT).sensitive(true).build()));
    private final ArchiveServer archive = new ArchiveServer();
    private final Outbound outbound = new Outbound(new Installation(false, true, false, null, null), ref -> Optional.empty());
    private final List<String> noted = new ArrayList<>();
    private final List<String> accepted = new ArrayList<>();
    private final List<DeliveryPort.DeliveryReference> acknowledged = new ArrayList<>();
    private Runnable afterAcknowledge = () -> { };
    private String secret = SECRET;
    @TempDir Path folder;
    private OneConnection repository;

    @BeforeEach
    void profileFile() throws Exception {
        try (InputStream in = getClass().getResourceAsStream("/destination/records-archive-v1.profile.json")) {
            Files.copy(in, folder.resolve("archive.json"));
        }
        repository = new OneConnection(connection(destination(true, Map.of(), List.of("member_ref"), DestinationSettings.CaseLevel.REFUSE), 2));
    }

    @AfterEach
    void stop() {
        archive.close();
        outbound.close();
    }

    // ---- what is sent

    @Test
    void theCaseGoesOutOnceWithItsIdsLevelsAndFieldsTyped() {
        var items = List.of(item("doc-b", SensitivityLevel.HIGHLY_CONFIDENTIAL), item("doc-a", SensitivityLevel.STANDARD));
        var result = adapter(true).deliverBatch(batch(items, SensitivityLevel.STANDARD, null, allFields()));

        assertFalse(result.alreadyReceived());
        assertEquals("dlv-1", result.destinationSubmissionReference());
        assertEquals(Map.of("sub-1", "dlv-1"), archive.deliveries);
        assertEquals(Set.of("doc-a", "doc-b"), archive.files.keySet());
        assertEquals("HIGHLY_CONFIDENTIAL", archive.levels.get("doc-b"));
        assertEquals("STANDARD", archive.levels.get("doc-a"));
        String open = archive.of("/v1/deliveries").get(0).body();
        assertTrue(open.contains("\"count\":2"), open);
        assertTrue(open.contains("\"amount\":1250.5"), "a number as a number: " + open);
        assertTrue(open.contains("\"opened_on\":\"2026-11-25\""), open);
        assertTrue(open.contains("\"contract_number\":\"LN-1\""), open);
        assertTrue(open.contains("\"member_ref\":\"M-77\""), "allowed by IT: " + open);
        var upload = archive.of("/v1/deliveries/.*/files").get(0);
        assertEquals("application/pdf", upload.headers().get("content-type"));
        assertEquals("dlv-1", result.destinationSubmissionReference());
        assertEquals("file-doc-a", result.documents().get(0).destinationDocumentReference());
        assertEquals(List.of("dlv-1"), accepted);
        assertEquals(IntegrationHealthStatus.HEALTHY, repository.current.healthStatus());
        assertTrue(noted.stream().noneMatch(n -> n.contains("LN-1") || n.contains("M-77") || n.contains("token-")), "no value in the progress");
    }

    @Test
    void theOrderOfTheBatchDoesNotChangeTheCall() {
        var a = item("doc-a", SensitivityLevel.STANDARD);
        var b = item("doc-b", SensitivityLevel.STANDARD);
        archive.faults.add(new ArchiveServer.Fault(503, "ARC-DOWN", null, true)); // stored, answer lost
        fails(adapter(true), batch(List.of(a, b), SensitivityLevel.STANDARD, null, allFields()), LvErrorCode.DLV_002);
        adapter(true).deliverBatch(batch(List.of(b, a), SensitivityLevel.STANDARD, noted.get(noted.size() - 1), allFields()));
        assertEquals(1, archive.deliveries.size());
        assertEquals(2, archive.files.size());
        assertEquals(archive.of("/v1/deliveries").get(0).body(), archive.of("/v1/deliveries").get(1).body());
    }

    @Test
    void onlyTheMappedFieldsGoOutUnderTheDestinationsNamesAndAMissingOneIsLeftOut() {
        set(destination(false, Map.of("loanNo", "contract_number", "sum", "amount"), List.of(), DestinationSettings.CaseLevel.REFUSE));
        adapter(true).deliverBatch(batch(List.of(item("doc-a", SensitivityLevel.STANDARD)), SensitivityLevel.STANDARD, null,
                Map.of("contract_number", "LN-1", "opened_on", "2026-11-25")));
        assertTrue(archive.of("/v1/deliveries").get(0).body().contains("\"fields\":{\"loanNo\":\"LN-1\"}"));
    }

    @Test
    void aSensitiveFieldItDidNotAllowStopsTheDeliveryBeforeAnythingIsSent() {
        set(destination(true, Map.of(), List.of(), DestinationSettings.CaseLevel.REFUSE));
        var e = fails(adapter(true), batch(List.of(item("doc-a", SensitivityLevel.STANDARD)), SensitivityLevel.STANDARD, null, allFields()), LvErrorCode.DLV_006);
        assertEquals("INT_SENSITIVE_FIELD_NOT_ALLOWED", e.destinationCode());
        assertTrue(archive.calls.isEmpty());
    }

    @Test
    void valuesThatCanNotBeReadAsTypedAreNotLeftOutSilently() {
        var batch = new DeliveryPort.DeliveryBatch(tenant, PackageId.generate(), 1, "sub-1", null, Map.of("contract_number", "LN-1"),
                Map.of(), List.of(item("doc-a", SensitivityLevel.STANDARD)), listener(), SensitivityLevel.STANDARD, null,
                new DeliveryPort.DeliveryContext(null, fieldSet, List.of()));
        assertEquals("INT_FIELD_VALUES_INVALID", fails(adapter(true), batch, LvErrorCode.DLV_006).destinationCode());
        assertTrue(archive.calls.isEmpty());
    }

    // ---- levels

    @Test
    void aCaseLevelTheProfileHasNoPlaceForIsRefusedOrGoesOnEveryDocumentAsItChose() {
        var items = List.of(item("doc-a", SensitivityLevel.STANDARD), item("doc-b", SensitivityLevel.HIGHLY_CONFIDENTIAL));
        var e = fails(adapter(true), batch(items, SensitivityLevel.CONFIDENTIAL, null, Map.of()), LvErrorCode.DLV_003);
        assertEquals(TechnicalFailureCode.DELIVERY_SENSITIVITY_REFUSED, e.failureCode());
        assertFalse(e.retryable());
        assertTrue(archive.calls.isEmpty());

        set(destination(true, Map.of(), List.of(), DestinationSettings.CaseLevel.ON_EACH_DOCUMENT));
        adapter(true).deliverBatch(batch(items, SensitivityLevel.CONFIDENTIAL, null, Map.of()));
        assertEquals("CONFIDENTIAL", archive.levels.get("doc-a"));
        assertEquals("HIGHLY_CONFIDENTIAL", archive.levels.get("doc-b"), "never lowered");
    }

    @Test
    void aRefusedLevelIsLvDlv003WhenTheCodeIsOneItListedAndAnotherRefusalIsLvDlv004() {
        archive.faults.add(new ArchiveServer.Fault(400, "ARC-LEVEL-1", null, false));
        var e = fails(adapter(true), one(), LvErrorCode.DLV_003);
        assertEquals("ARC-LEVEL-1", e.destinationCode());
        assertFalse(e.retryable());
        archive.faults.add(new ArchiveServer.Fault(400, "ARC-FIELD", null, false));
        assertEquals("ARC-FIELD", fails(adapter(true), one(), LvErrorCode.DLV_004).destinationCode());
    }

    @Test
    void anUnsafeDestinationCodeIsNotRepeated() {
        archive.faults.add(new ArchiveServer.Fault(400, "LN-1 <script>", null, false));
        assertEquals("INT_REFUSED", fails(adapter(true), one(), LvErrorCode.DLV_004).destinationCode());
    }

    // ---- outcomes

    @Test
    void busyIsNotAFailureAndKeepsItsWaitCappedAtOneHour() {
        archive.faults.add(new ArchiveServer.Fault(503, "ARC-BUSY", "120", false));
        var e = fails(adapter(true), one(), LvErrorCode.DLV_002);
        assertTrue(e.rateLimited());
        assertEquals(Duration.ofSeconds(120), e.retryAfter());
        archive.faults.add(new ArchiveServer.Fault(503, "ARC-BUSY", "86400", false));
        assertEquals(Duration.ofHours(1), fails(adapter(true), one(), LvErrorCode.DLV_002).retryAfter());
    }

    @Test
    void a429WithoutARuleIsBusyToo() {
        archive.faults.add(new ArchiveServer.Fault(429, "X", "3", false));
        assertTrue(fails(adapter(true), one(), LvErrorCode.DLV_002).rateLimited());
    }

    @Test
    void anOutageIsRetryableAndNotARateLimit() {
        archive.faults.add(new ArchiveServer.Fault(503, "ARC-DOWN", null, false));
        var e = fails(adapter(true), one(), LvErrorCode.DLV_002);
        assertTrue(e.retryable());
        assertFalse(e.rateLimited());
        assertEquals(IntegrationHealthStatus.DEGRADED, repository.current.healthStatus());
    }

    @Test
    void nobodyThereIsLvDlv001() {
        set(c -> withEndpoint(c, "http://127.0.0.1:1"));
        var e = fails(adapter(true), one(), LvErrorCode.DLV_001);
        assertTrue(e.retryable());
        assertEquals("INT_CANT_CONNECT", e.destinationCode());
    }

    @Test
    void aConflictIsRefusedNeverAlreadyReceived() {
        archive.faults.add(new ArchiveServer.Fault(409, "ARC-CONFLICT", null, false));
        assertFalse(fails(adapter(true), one(), LvErrorCode.DLV_004).retryable());
    }

    @Test
    void anotherChecksumThanTheOneSentIsAnUnclearAnswer() {
        archive.wrongChecksum = "0".repeat(64);
        var e = fails(adapter(true), one(), LvErrorCode.DLV_007);
        assertTrue(e.retryable());
        assertEquals("INT_INTEGRITY_MISMATCH", e.destinationCode());
        assertTrue(acknowledged.isEmpty());
    }

    @Test
    void signInFailuresAreLvDlv005AndASecretIsNeverRepeated() {
        secret = null;
        var e = fails(adapter(true), one(), LvErrorCode.DLV_005);
        assertEquals("INT_SECRET_NOT_FOUND", e.destinationCode());
        assertFalse(e.retryable());
    }

    @Test
    void aRefusedTokenIsFetchedAgainOnceThenItIsLvDlv005() {
        archive.refuseToken = "Bearer token-1";
        adapter(true).deliverBatch(one());
        assertEquals(2, archive.tokens, "the held token was refused once; a new one worked");
        assertEquals(1, archive.files.size());

        archive.faults.add(new ArchiveServer.Fault(401, "ARC-TOKEN", null, false));
        archive.faults.add(new ArchiveServer.Fault(401, "ARC-TOKEN", null, false));
        assertFalse(fails(adapter(true), batch(List.of(item("doc-z", SensitivityLevel.STANDARD)), SensitivityLevel.STANDARD, null, Map.of(), "sub-2"),
                LvErrorCode.DLV_005).retryable());
    }

    // ---- nothing twice

    @Test
    void theDestinationSayingItAlreadyHasItCountsAsDelivered() {
        adapter(true).deliverBatch(one());
        var again = adapter(true).deliverBatch(one()); // the same identities, the progress lost
        assertTrue(again.alreadyReceived());
        assertEquals(1, archive.deliveries.size());
        assertEquals(1, archive.files.size());
        assertEquals("file-doc-a", again.documents().get(0).destinationDocumentReference());
    }

    @Test
    void theNextAttemptGoesOnAfterTheLastDocumentThatWasAcknowledged() {
        var a = item("doc-a", SensitivityLevel.STANDARD);
        var b = item("doc-b", SensitivityLevel.STANDARD);
        afterAcknowledge = () -> archive.faults.add(new ArchiveServer.Fault(503, "ARC-DOWN", null, false));
        fails(adapter(true), batch(List.of(a, b), SensitivityLevel.STANDARD, null, allFields()), LvErrorCode.DLV_002);
        afterAcknowledge = () -> { };
        assertEquals(List.of("doc-a"), acknowledged.stream().map(DeliveryPort.DeliveryReference::sourceDocumentId).toList());

        // Another server: only what is stored is known.
        var rest = new DeliveryPort.DeliveryBatch(tenant, PackageId.generate(), 1, "sub-1", "dlv-1", Map.of(), Map.of(), List.of(b),
                listener(), SensitivityLevel.STANDARD, document(allFields()),
                new DeliveryPort.DeliveryContext(noted.get(noted.size() - 1), fieldSet, List.of(a)));
        var done = adapter(true).deliverBatch(rest);
        assertEquals("dlv-1", done.destinationSubmissionReference());
        assertEquals(1, archive.of("/v1/deliveries").size(), "opened once");
        assertEquals(1, archive.of("/v1/deliveries/.*/files").stream().filter(c -> "doc-a".equals(c.headers().get("x-archive-item-ref"))).count());
        assertEquals(Set.of("doc-a", "doc-b"), archive.files.keySet());
    }

    @Test
    void aLostAnswerIsRepeatedWithTheSameIdsAndStoredOnce() {
        archive.faults.add(new ArchiveServer.Fault(503, "ARC-DOWN", null, true));
        fails(adapter(true), one(), LvErrorCode.DLV_002);
        afterAcknowledge = () -> { };
        var done = adapter(true).deliverBatch(batch(List.of(item("doc-a", SensitivityLevel.STANDARD)), SensitivityLevel.STANDARD,
                noted.get(noted.size() - 1), Map.of()));
        assertFalse(done.alreadyReceived(), "opening it again is not 'already received'; the document was new");
        assertEquals(1, archive.deliveries.size());
        assertEquals(1, archive.files.size());
    }

    @Test
    void aDeliveryHalfDoneByAnEarlierReleaseStopsClosed() {
        var old = new DeliveryPort.DeliveryBatch(tenant, PackageId.generate(), 1, "sub-1", "remote-77", Map.of(), Map.of(),
                List.of(item("doc-b", SensitivityLevel.STANDARD)), listener(), SensitivityLevel.STANDARD, null,
                new DeliveryPort.DeliveryContext(null, fieldSet, List.of(item("doc-a", SensitivityLevel.STANDARD))));
        var e = fails(adapter(true), old, LvErrorCode.DLV_016);
        assertFalse(e.retryable());
        var allAcknowledged = new DeliveryPort.DeliveryBatch(tenant, PackageId.generate(), 1, "sub-1", "remote-77", Map.of(), Map.of(),
                List.of(), listener(), SensitivityLevel.STANDARD, null,
                new DeliveryPort.DeliveryContext(null, fieldSet, List.of(item("doc-a", SensitivityLevel.STANDARD))));
        fails(adapter(true), allAcknowledged, LvErrorCode.DLV_016);
        assertTrue(archive.calls.isEmpty(), "nothing is sent");
    }

    @Test
    void progressOfAnotherAddressOrAnUnreadableOneIsNeverUsed() {
        archive.faults.add(new ArchiveServer.Fault(503, "ARC-DOWN", null, true));
        fails(adapter(true), one(), LvErrorCode.DLV_002);
        String progress = noted.get(noted.size() - 1);
        set(c -> withEndpoint(c, archive.address() + "/moved"));
        fails(adapter(true), batch(List.of(item("doc-a", SensitivityLevel.STANDARD)), SensitivityLevel.STANDARD, progress, Map.of()), LvErrorCode.DLV_016);
        assertThrows(RuntimeException.class, () -> adapter(true).deliverBatch(
                batch(List.of(item("doc-a", SensitivityLevel.STANDARD)), SensitivityLevel.STANDARD, "{broken", Map.of())));
    }

    // ---- tries

    @Test
    void inJobModeThereIsOneTryTheJobSpacesTheTries() {
        for (int i = 0; i < 3; i++) archive.faults.add(new ArchiveServer.Fault(503, "ARC-DOWN", null, false));
        assertEquals(1, fails(adapter(true), one(), LvErrorCode.DLV_002).attempts());
        assertEquals(1, archive.of("/v1/deliveries").size());
    }

    @Test
    void inInlineModeAnExportJobsAttemptIsStillOneTry() throws Exception {
        for (int i = 0; i < 3; i++) archive.faults.add(new ArchiveServer.Fault(503, "ARC-DOWN", null, false));
        ExportJobAttempt.run(() -> {
            fails(adapter(false), one(), LvErrorCode.DLV_002);
            return null;
        });
        assertEquals(1, archive.of("/v1/deliveries").size());
    }

    @Test
    void inlineKeepsTheTriesInTheAdapterBusyIncluded() {
        for (int i = 0; i < 3; i++) archive.faults.add(new ArchiveServer.Fault(503, "ARC-BUSY", "1", false));
        assertEquals(3, fails(adapter(false), one(), LvErrorCode.DLV_002).attempts(), "1 + 2");
        assertEquals(3, archive.of("/v1/deliveries").size());
    }

    // ---- set-up

    @Test
    void aConnectionWithoutAProfileIsRefusedWithItsCodeAndSendsNothing() {
        set((DestinationSettings) null);
        assertEquals("DESTINATION_PROFILE_MISSING", fails(adapter(true), one(), LvErrorCode.DLV_006).destinationCode());
        var e = assertThrows(TechnicalDependencyException.class, () -> adapter(true).checkDestination(tenant));
        assertEquals(LvErrorCode.DLV_006, LvErrorCodes.of(e));
        assertTrue(archive.calls.isEmpty());
    }

    @Test
    void aProfileFileThatChangedItsVersionIsRefusedUntilItIsSavedAgain() throws Exception {
        Path file = folder.resolve("archive.json");
        Files.writeString(file, Files.readString(file).replaceFirst("\"version\": \"1.0.0\"", "\"version\": \"1.1.0\""));
        assertEquals("DESTINATION_PROFILE_CHANGED", fails(adapter(true), one(), LvErrorCode.DLV_006).destinationCode());
    }

    @Test
    void theHealthCheckSendsNoDocumentEvenWhenTheProfilesTestWould() {
        adapter(true).checkDestination(tenant);
        assertEquals(List.of("/ready", "/token"), archive.calls.stream().map(ArchiveServer.Call::path).toList());
        archive.health = 503;
        assertEquals(LvErrorCode.DLV_002, LvErrorCodes.of(assertThrows(TechnicalDependencyException.class, () -> adapter(true).checkDestination(tenant))));
        archive.healthCode = "ARC-BUSY";
        adapter(true).checkDestination(tenant); // busy is not down
    }

    @Test
    void savingChecksTheProfileTheFieldsTheSecretAndTheAddress() {
        DestinationProfiles profiles = profiles();
        var good = profiles.checked(repository.current.withDestination(
                new DestinationSettings("archive.json", null, null, archive.tokenAddress(), "lexavault", null, null, false, true, Map.of(),
                        List.of(), null, List.of(), Map.of())));
        assertEquals("records-archive-v1", good.destination().profileName());
        assertEquals("1.0.0", good.destination().profileVersion());
        for (DestinationSettings bad : List.of(
                new DestinationSettings("missing.json", null, null, archive.tokenAddress(), "lexavault", null, null, false, true, Map.of(), List.of(), null, List.of(), Map.of()),
                new DestinationSettings("archive.json", null, null, archive.tokenAddress(), "lexavault", null, null, false, false, Map.of("x", "no_such_field"), List.of(), null, List.of(), Map.of()),
                new DestinationSettings("archive.json", null, null, archive.tokenAddress(), "lexavault", null, null, false, true, Map.of(), List.of("no_such_field"), null, List.of(), Map.of()),
                new DestinationSettings("archive.json", null, null, archive.tokenAddress(), "lexavault", null, null, false, true, Map.of(), List.of(), null, List.of(), Map.of("nothing", "x")))) {
            assertEquals(LvErrorCode.CFG_003, assertThrows(ValidationException.class,
                    () -> profiles.checked(repository.current.withDestination(bad))).lvCode());
        }
        IntegrationConnection otherSecret = IntegrationConnection.create(tenant, "Store", null, IntegrationPurpose.DOCUMENT_STORE_DELIVERY,
                IntegrationDirection.OUTBOUND, IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.JWT, null, archive.address(), null,
                "env:DB_PASSWORD", 5, 0, 24, 10, 10_000_000L, 100, IntegrationAcknowledgementMode.SUBMISSION_ACK, true, Instant.now())
                .withDestination(repository.current.destination());
        assertThrows(ValidationException.class, () -> profiles.checked(otherSecret));
        assertThrows(ValidationException.class, () -> new DestinationProfiles("", new Installation(false, true, false, null, null),
                (t, r) -> SecretRef.of(r), t -> Set.of()).checked(repository.current));
        // Plain http is refused where internal addresses are not allowed.
        DestinationProfiles strict = new DestinationProfiles(folder.toString(), new Installation(false, false, false, null, null),
                (t, r) -> SecretRef.of(r), t -> keys());
        assertThrows(ValidationException.class, () -> strict.checked(repository.current));
    }

    @Test
    void aDamagedProfileIsListedWithItsProblemsAndWithoutAName() throws Exception {
        Files.writeString(folder.resolve("bad.json"), "{\"format\":\"platform-integration-profile/1\"}");
        var all = profiles().list();
        assertEquals(List.of("archive.json", "bad.json"), all.stream().map(p -> p.file()).toList());
        assertTrue(all.get(0).problems().isEmpty());
        assertTrue(all.get(0).carriesLevel());
        assertFalse(all.get(0).carriesCaseLevel());
        assertNull(all.get(1).name());
        assertFalse(all.get(1).problems().isEmpty());
    }

    @Test
    void noFileIsWrittenWhileADocumentIsSent() {
        Path temp = Path.of(System.getProperty("java.io.tmpdir"));
        Set<String> before = names(temp);
        Set<String> during = new java.util.HashSet<>();
        byte[] pdf = "%PDF-a-big-one".repeat(200_000).getBytes();
        var item = new DeliveryPort.DeliveryItem(DocumentId.generate(), "doc-a", "a.pdf", () -> new ByteArrayInputStream(pdf) {
            @Override
            public synchronized int read(byte[] b, int off, int len) {
                during.addAll(names(temp));
                return super.read(b, off, len);
            }
        }, pdf.length, "application/pdf", "SHA-256", ArchiveServer.sha(pdf), SensitivityLevel.STANDARD);
        adapter(true).deliverBatch(batch(List.of(item), SensitivityLevel.STANDARD, null, Map.of()));
        during.removeAll(before);
        assertTrue(during.stream().noneMatch(n -> n.contains("platform") || n.contains("integration") || n.contains("httpclient")), during.toString());
        assertEquals(1, archive.files.size());
    }

    // ---- fixture

    private static Set<String> names(Path dir) {
        String[] all = dir.toFile().list();
        return all == null ? Set.of() : Set.of(all);
    }

    private Set<String> keys() {
        return Set.of("contract_number", "amount", "opened_on", "member_ref");
    }

    private DestinationProfiles profiles() {
        String prefix = "env:LEXAVAULT_SECRET_" + tenant.value().toString().replace("-", "").toUpperCase() + "_";
        return new DestinationProfiles(folder.toString(), new Installation(false, true, false, null, null), (t, r) -> {
            if (r == null || !r.startsWith(prefix)) throw new IllegalArgumentException("not allowed");
            return SecretRef.of(r);
        }, t -> keys());
    }

    private ProfileDestinationDeliveryAdapter adapter(boolean single) {
        DestinationProfiles profiles = profiles();
        CallEngine engine = new CallEngine(outbound, profiles.secretResolver(
                ref -> secret == null ? Optional.empty() : Optional.of(Secret.of(secret))), Clock.systemUTC());
        return new ProfileDestinationDeliveryAdapter(repository, profiles, engine, single, Clock.systemUTC());
    }

    private TechnicalDependencyException fails(DeliveryPort adapter, DeliveryPort.DeliveryBatch batch, LvErrorCode code) {
        TechnicalDependencyException e = assertThrows(TechnicalDependencyException.class, () -> adapter.deliverBatch(batch));
        assertEquals(code, LvErrorCodes.of(e), e.getMessage() + " " + e.destinationCode());
        assertFalse(String.valueOf(e).contains(SECRET));
        return e;
    }

    private Map<String, String> allFields() {
        return Map.of("contract_number", "LN-1", "amount", "1250.50", "opened_on", "2026-11-25", "member_ref", "M-77");
    }

    private String document(Map<String, String> fields) {
        if (fields.isEmpty()) return null;
        Map<String, List<FieldValue>> values = new LinkedHashMap<>();
        fields.forEach((k, v) -> values.put(k, List.of(FieldValue.text(v))));
        var valid = fieldSet.validate(values);
        assertTrue(valid.accepted());
        return FieldDelivery.write(fieldSet, valid);
    }

    private DeliveryPort.DeliveryBatch one() {
        return batch(List.of(item("doc-a", SensitivityLevel.STANDARD)), SensitivityLevel.STANDARD, null, Map.of());
    }

    private DeliveryPort.DeliveryBatch batch(List<DeliveryPort.DeliveryItem> items, SensitivityLevel level, String progress, Map<String, String> fields) {
        return batch(items, level, progress, fields, "sub-1");
    }

    private DeliveryPort.DeliveryBatch batch(List<DeliveryPort.DeliveryItem> items, SensitivityLevel level, String progress,
                                             Map<String, String> fields, String id) {
        return new DeliveryPort.DeliveryBatch(tenant, PackageId.generate(), 1, id, null, fields, Map.of(), items, listener(), level,
                document(fields), new DeliveryPort.DeliveryContext(progress, fieldSet, List.of()));
    }

    private DeliveryPort.DeliveryProgressListener listener() {
        return new DeliveryPort.DeliveryProgressListener() {
            @Override public void submissionAccepted(String ref) { accepted.add(ref); }
            @Override public void documentAcknowledged(DeliveryPort.DeliveryReference ref) {
                acknowledged.add(ref);
                afterAcknowledge.run();
            }
            @Override public void progressed(String progress) { noted.add(progress); }
        };
    }

    private static DeliveryPort.DeliveryItem item(String id, SensitivityLevel level) {
        byte[] pdf = ("%PDF-" + id).getBytes();
        return new DeliveryPort.DeliveryItem(DocumentId.generate(), id, id + " redacted.pdf", () -> new ByteArrayInputStream(pdf), pdf.length,
                "application/pdf", "SHA-256", ArchiveServer.sha(pdf), level);
    }

    private DestinationSettings destination(boolean all, Map<String, String> mapping, List<String> sensitive, DestinationSettings.CaseLevel level) {
        return new DestinationSettings("archive.json", "records-archive-v1", "1.0.0", archive.tokenAddress(), "lexavault", null, null, false,
                all, mapping, sensitive, level, List.of("ARC-LEVEL-*"), Map.of());
    }

    private IntegrationConnection connection(DestinationSettings d, int retries) {
        return IntegrationConnection.create(tenant, "Store", null, IntegrationPurpose.DOCUMENT_STORE_DELIVERY, IntegrationDirection.OUTBOUND,
                IntegrationProviderType.HTTP_API, IntegrationAuthenticationType.OAUTH2_CLIENT_CREDENTIALS, null, archive.address(), null,
                "env:LEXAVAULT_SECRET_" + tenant.value().toString().replace("-", "").toUpperCase() + "_STORE", 5, retries, 24, 10, 10_000_000L, 100,
                IntegrationAcknowledgementMode.SUBMISSION_ACK, true, Instant.now()).withDestination(d);
    }

    private void set(DestinationSettings d) {
        repository.current = repository.current.withDestination(d);
    }

    private void set(java.util.function.UnaryOperator<IntegrationConnection> change) {
        repository.current = change.apply(repository.current);
    }

    private static IntegrationConnection withEndpoint(IntegrationConnection c, String endpoint) {
        DestinationSettings d = c.destination();
        return c.withDestination(null).updateConfiguration(c.name(), null, c.purpose(), c.direction(), c.providerType(), c.authenticationType(), null,
                endpoint, null, null, true, null, null, null, null, null, null, null, null, null, null, null, null, Instant.now()).withDestination(d);
    }

    static final class OneConnection implements IntegrationConnectionRepository {
        IntegrationConnection current;
        OneConnection(IntegrationConnection current) { this.current = current; }
        public List<IntegrationConnection> findByTenantId(TenantId tenantId) { return List.of(current); }
        public Optional<IntegrationConnection> findByTenantIdAndId(TenantId tenantId, IntegrationConnectionId id) { return Optional.of(current); }
        public Optional<IntegrationConnection> findEnabledPushSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal) { return Optional.empty(); }
        public Optional<IntegrationConnection> findEnabledScannerSourceByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal) { return Optional.empty(); }
        public Optional<IntegrationConnection> findEnabledDocumentStoreByTenantId(TenantId tenantId) { return Optional.of(current); }
        public boolean existsByTenantIdAndNameIgnoreCase(TenantId tenantId, String name, IntegrationConnectionId excludeId) { return false; }
        public boolean existsByTenantIdAndClientPrincipal(TenantId tenantId, String clientPrincipal, IntegrationConnectionId excludeId) { return false; }
        public void save(IntegrationConnection connection) { current = connection; }
    }
}
