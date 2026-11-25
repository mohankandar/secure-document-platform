package com.securedoc.platform.application.casefields;

import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.Normalisation;
import com.documentplatform.fields.SearchMode;
import com.documentplatform.fields.delivery.FieldDelivery;
import com.documentplatform.fields.values.RecordRef;
import com.securedoc.platform.application.exception.IntakeValidationException;
import com.securedoc.platform.application.exception.TechnicalDependencyException;
import com.securedoc.platform.application.exception.TechnicalFailureCode;
import com.securedoc.platform.application.intake.model.IntakeRejectionCode;
import com.securedoc.platform.domain.identity.BusinessRecordId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0061: values of case fields through platform-fields. The first four tests are the ones of
 * {@code BusinessIdentityServiceTest}, with the same values and the same outcomes.
 */
class CaseFieldServiceTest {
    private static final String NAME = "Synthetic Borrower Quill";

    private final TenantId tenant = TenantId.generate();
    private final TestCaseFields fields = new TestCaseFields();

    private CaseFieldService text() {
        fields.add(tenant, upper("contract_number", "Contract Number", 1).required(true).primary(true));
        fields.add(tenant, upper("member_number", "Member Number", 2).required(true));
        fields.add(tenant, upper("service_entity", "Service Entity", 3).required(true));
        return fields.service();
    }

    private static FieldSpec.Builder upper(String key, String label, int order) {
        return FieldSpec.builder(key, label, FieldType.TEXT).searchable(true).displayable(true).displayOrder(order)
                .normalisation(Normalisation.LetterCase.UPPER, Normalisation.Strip.NONE);
    }

    @Test
    void newPrimaryCreatesNewRecordWhenSecondaryFieldsRepeat() {
        CaseFieldService service = text();
        var first = service.resolveOrCreate(tenant, contract("C-100", "M-200", "000"));
        var result = service.resolveOrCreate(tenant, contract("C-101", "M-200", "000"));
        assertTrue(result.createdNew());
        assertNotEquals(first.businessRecordId(), result.businessRecordId());
        assertEquals("C-101", value(service, result, "contract_number"));
        assertEquals("M-200", value(service, result, "member_number"));
        assertEquals("000", value(service, result, "service_entity"));
    }

    @Test
    void samePrimaryJoinsTheBusinessRecordWhenSecondaryFieldsMatch() {
        CaseFieldService service = text();
        var first = service.resolveOrCreate(tenant, contract("C-100", "M-200", "000"));
        var result = service.resolveOrCreate(tenant, contract("C-100", "M-200", "000"));
        assertFalse(result.createdNew());
        assertEquals(first.businessRecordId(), result.businessRecordId());
    }

    @Test
    void samePrimaryRefusesADifferentSecondaryValueAndAddsAMissingOne() {
        CaseFieldService service = text();
        fields.add(tenant, upper("branch", "Branch", 4));
        var first = service.resolveOrCreate(tenant, contract("C-100", "M-200", "000"));
        IntakeValidationException error = assertThrows(IntakeValidationException.class,
                () -> service.resolveOrCreate(tenant, contract("C-100", "M-DIFFERENT", "000")));
        assertEquals(IntakeRejectionCode.IDENTIFIER_CONFLICT, error.rejectionCode());
        assertTrue(error.getMessage().contains("Member Number"), error.getMessage());
        assertFalse(error.getMessage().contains("Contract Number"), error.getMessage());
        assertFalse((error.getMessage() + error.detail()).contains("M-DIFFERENT"));

        service.resolveOrCreate(tenant, Map.of("contract_number", "C-100", "member_number", "M-200", "service_entity", "000",
                "branch", "North"));
        assertEquals("North", value(service, first, "branch"));
    }

    @Test
    void numberAndListRulesAreEnforcedWithoutDroppingLeadingZeroes() {
        fields.add(tenant, FieldSpec.builder("contract_number", "Contract Number", FieldType.NUMBER).pattern("[0-9]+")
                .exactLength(12).required(true).primary(true).displayOrder(1));
        fields.add(tenant, FieldSpec.builder("member_number", "Member Number", FieldType.NUMBER).pattern("[0-9]+")
                .exactLength(11).required(true).displayOrder(2));
        fields.add(tenant, FieldSpec.builder("service_entity", "Service Entity", FieldType.DROPDOWN)
                .options("000", "001", "005").required(true).displayOrder(3));
        CaseFieldService service = fields.service();
        var accepted = service.resolveOrCreate(tenant, contract("000642500012", "00202123456", "005"));
        assertEquals("000642500012", value(service, accepted, "contract_number"));
        assertEquals("00202123456", value(service, accepted, "member_number"));
        refused(service, contract("642500012", "00202123456", "005"), "Contract Number");
        refused(service, contract("000642500014", "0020212345A", "005"), "Member Number");
        refused(service, contract("000642500013", "00202123456", "006"), "Service Entity");
    }

    private void refused(CaseFieldService service, Map<String, String> given, String label) {
        IntakeValidationException e = assertThrows(IntakeValidationException.class, () -> service.resolveOrCreate(tenant, given));
        assertEquals(IntakeRejectionCode.INVALID_IDENTIFIER_VALUE, e.rejectionCode());
        assertTrue(e.getMessage().contains(label), e.getMessage());
        for (String value : given.values()) assertFalse((e.getMessage() + e.detail()).contains(value), e.getMessage());
    }

    @Test
    void everyTypeIsTakenAndShownAsPeopleReadIt() {
        CaseFieldService service = text();
        fields.add(tenant, FieldSpec.builder("amount", "Amount", FieldType.MONEY).currency("USD").displayOrder(4));
        fields.add(tenant, FieldSpec.builder("rate", "Rate", FieldType.DECIMAL).displayOrder(5));
        fields.add(tenant, FieldSpec.builder("opened", "Opened", FieldType.DATE).displayOrder(6));
        fields.add(tenant, FieldSpec.builder("signed_at", "Signed at", FieldType.DATE_TIME).displayOrder(7));
        fields.add(tenant, FieldSpec.builder("property", "Property", FieldType.ADDRESS).sensitive(false).displayOrder(8));
        fields.add(tenant, FieldSpec.builder("email", "Email", FieldType.EMAIL).multiValue(true).displayOrder(9));
        fields.add(tenant, FieldSpec.builder("code", "Code", FieldType.CUSTOM_PATTERN).pattern("[A-Z]{2}[0-9]{2}").displayOrder(10));
        var record = service.resolveOrCreate(tenant, Map.ofEntries(Map.entry("Contract_Number", "C-1"), Map.entry("member_number", "M"),
                Map.entry("service_entity", "S"), Map.entry("amount", "$1,250.50"), Map.entry("rate", "4.125"),
                Map.entry("opened", "11/21/2026"), Map.entry("signed_at", "2026-11-21T10:15:30Z"),
                Map.entry("property", CaseFieldText.address("1 Elm Street", "Unit 2", "Springfield", "IL", "62701", null)),
                Map.entry("email", CaseFieldText.several(List.of("a@example.test", "b@example.test"))), Map.entry("code", "AB12")));
        assertEquals("1 Elm Street, Unit 2, Springfield, IL 62701", value(service, record, "property"));
        assertEquals("a@example.test; b@example.test", value(service, record, "email"));
        assertEquals(10, service.get(record.businessRecordId(), tenant).values().size());

        CaseFieldService.Delivery delivery = service.forDelivery(record.businessRecordId(), tenant);
        var received = FieldDelivery.verify(fields.definitions.fieldSet(CaseFieldService.scope(tenant)), delivery.document());
        assertTrue(received.accepted(), received.problems().toString());
        assertEquals(2, received.values().get(com.documentplatform.fields.FieldKey.of("email")).size());
        assertFalse(delivery.toString().contains("Elm"));

        // An address for a text field, or two values for a field that takes one.
        for (String notForIt : List.of(CaseFieldText.address("1 Elm", "", "X", "IL", "62701", ""), CaseFieldText.several(List.of("A", "B")))) {
            assertThrows(IntakeValidationException.class, () -> service.resolveOrCreate(tenant,
                    Map.of("contract_number", notForIt, "member_number", "M", "service_entity", "S")));
        }
    }

    @Test
    void whatIsUnclearIsRefused() {
        CaseFieldService service = text();
        Map<String, String> good = contract("C-9", "M", "S");
        Map<String, String> unknown = new java.util.HashMap<>(good);
        unknown.put("nobody_knows", "secret text");
        IntakeValidationException e = assertThrows(IntakeValidationException.class, () -> service.resolveOrCreate(tenant, unknown));
        assertEquals(IntakeRejectionCode.UNKNOWN_IDENTIFIER, e.rejectionCode());
        assertFalse((e.getMessage() + e.detail()).contains("nobody_knows"));
        Map<String, String> twice = new java.util.HashMap<>(good);
        twice.put("CONTRACT_NUMBER", "C-10");
        assertThrows(IntakeValidationException.class, () -> service.resolveOrCreate(tenant, twice));
        assertEquals(IntakeRejectionCode.IDENTIFIER_CONFIGURATION_MISSING, assertThrows(IntakeValidationException.class,
                () -> service.resolveOrCreate(TenantId.generate(), good)).rejectionCode());
    }

    @Test
    void aSensitiveValueIsSealedHiddenAndFoundOnlyExactly() {
        CaseFieldService service = text();
        fields.add(tenant, FieldSpec.builder("borrower_name", "Borrower name", FieldType.TEXT).sensitive(true).searchable(true)
                .displayOrder(4));
        var record = service.resolveOrCreate(tenant, Map.of("contract_number", "C-1", "member_number", "M", "service_entity", "S",
                "borrower_name", NAME));
        var stored = fields.values.find(ref(record.businessRecordId())).orElseThrow();
        stored.values().forEach(v -> assertFalse(String.join("|", String.valueOf(v.display()), String.valueOf(v.normalised()),
                String.valueOf(v.search()), String.valueOf(v.sealed()), String.valueOf(v.fingerprint())).contains("Quill")));
        assertEquals(CaseFieldSnapshot.HIDDEN, value(service, record, "borrower_name"));
        assertFalse(service.get(record.businessRecordId(), tenant).toString().contains("Quill"));
        var opened = service.getWithSensitive(record.businessRecordId(), tenant);
        assertTrue(opened.values().stream().anyMatch(v -> NAME.equals(v.value()) && v.sensitive()));
        assertFalse(opened.toString().contains("Quill"));

        assertEquals(List.of(record.businessRecordId().value()), service.findSensitive(tenant, "  synthetic borrower  QUILL "));
        assertEquals(List.of(), service.findSensitive(tenant, "Synthetic Borrower"));
        assertEquals(List.of(), service.findSensitive(TenantId.generate(), NAME));
        assertEquals(NAME, service.forDelivery(record.businessRecordId(), tenant).texts().get("borrower_name"));
    }

    @Test
    void aDigestHoldsOnlyAKeyedFingerprintOfASensitiveValueAlsoAfterAKeyRotation() {
        CaseFieldService service = text();
        fields.add(tenant, FieldSpec.builder("borrower_name", "Borrower name", FieldType.TEXT).sensitive(true));
        Map<String, String> sent = Map.of("contract_number", "C-1", "Borrower_Name", NAME);
        var before = service.forDigest(tenant, sent);
        assertEquals(1, before.size());
        assertEquals("C-1", before.getFirst().get("contract_number"));
        assertFalse(before.toString().contains("Quill"));
        fields.keys.rotateFingerprintKey(fields.customer);
        var after = service.forDigest(tenant, sent);
        assertEquals(2, after.size());
        assertNotEquals(before.getFirst(), after.getFirst());
        assertTrue(after.contains(before.getFirst()));
        Map<String, String> plain = Map.of("contract_number", "C-1");
        assertEquals(List.of(plain), service.forDigest(tenant, plain));
    }

    @Test
    void withoutTheKeyServiceASensitiveValueIsRefusedAndEverythingElseWorks() {
        TestCaseFields none = new TestCaseFields(false);
        none.add(tenant, FieldSpec.builder("contract_number", "Contract", FieldType.TEXT).required(true).primary(true));
        none.add(tenant, FieldSpec.builder("borrower_name", "Borrower name", FieldType.TEXT).sensitive(true));
        CaseFieldService service = none.service();
        assertTrue(service.resolveOrCreate(tenant, Map.of("contract_number", "C-1")).createdNew());
        TechnicalDependencyException e = assertThrows(TechnicalDependencyException.class,
                () -> service.resolveOrCreate(tenant, Map.of("contract_number", "C-2", "borrower_name", NAME)));
        assertEquals(TechnicalFailureCode.KEY_SERVICE_REFUSED, e.failureCode());
        assertFalse(String.valueOf(e).contains("Quill"));
        assertEquals(List.of(), service.findSensitive(tenant, NAME));
    }

    @Test
    void whenTheKeyServiceIsDownTheCaseOpensWithHiddenValuesAndNoDeliveryIsSentWithoutThem() {
        CaseFieldService service = text();
        fields.add(tenant, FieldSpec.builder("borrower_name", "Borrower name", FieldType.TEXT).sensitive(true).displayOrder(4));
        var record = service.resolveOrCreate(tenant, Map.of("contract_number", "C-1", "member_number", "M", "service_entity", "S",
                "borrower_name", NAME));
        fields.keys.down(true);
        assertTrue(service.getWithSensitive(record.businessRecordId(), tenant).values().stream()
                .anyMatch(v -> v.key().equals("borrower_name") && v.value().equals(CaseFieldSnapshot.HIDDEN)));
        TechnicalDependencyException e = assertThrows(TechnicalDependencyException.class,
                () -> service.forDelivery(record.businessRecordId(), tenant));
        assertEquals(TechnicalFailureCode.KEY_SERVICE_UNAVAILABLE, e.failureCode());
        assertEquals(TechnicalFailureCode.KEY_SERVICE_UNAVAILABLE, assertThrows(TechnicalDependencyException.class,
                () -> service.resolveOrCreate(tenant, Map.of("contract_number", "C-2", "member_number", "M",
                        "service_entity", "S", "borrower_name", NAME))).failureCode());
    }

    @Test
    void anErasedRecordHasNoValuesAndTheSamePrimaryMakesANewOne() {
        CaseFieldService service = text();
        BusinessRecordId gone = BusinessRecordId.generate();
        assertEquals(List.of(), service.get(gone, tenant).values());
        assertEquals(Map.of(), service.forDelivery(gone, tenant).texts());
        assertNull(service.forDelivery(gone, tenant).document());
    }

    @Test
    void claimsOfTheSamePrimaryValueAtTheSameTimeGetOneRecord() throws Exception {
        CaseFieldService service = text();
        int writers = 8;
        CyclicBarrier together = new CyclicBarrier(writers);
        Callable<CaseFieldSnapshot> claim = () -> {
            together.await();
            return service.resolveOrCreate(tenant, contract("C-777", "M", "S"));
        };
        try (var pool = Executors.newFixedThreadPool(writers)) {
            var results = pool.invokeAll(java.util.Collections.nCopies(writers, claim));
            java.util.Set<BusinessRecordId> ids = new java.util.HashSet<>();
            int created = 0;
            for (var result : results) {
                ids.add(result.get().businessRecordId());
                if (result.get().createdNew()) created++;
            }
            assertEquals(1, ids.size());
            assertEquals(1, created);
        }
    }

    private static Map<String, String> contract(String contract, String member, String service) {
        return Map.of("contract_number", contract, "member_number", member, "service_entity", service);
    }

    private RecordRef ref(BusinessRecordId id) {
        return RecordRef.of(CaseFieldService.scope(tenant), CaseFieldService.RECORD_TYPE, id.value().toString());
    }

    private String value(CaseFieldService service, CaseFieldSnapshot record, String key) {
        return service.get(record.businessRecordId(), tenant).values().stream().filter(v -> v.key().equals(key)).findFirst()
                .orElseThrow().value();
    }
}
