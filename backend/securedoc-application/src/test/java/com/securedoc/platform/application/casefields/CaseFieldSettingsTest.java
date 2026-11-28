package com.securedoc.platform.application.casefields;

import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldType;
import com.documentplatform.fields.SearchMode;
import com.securedoc.platform.application.casefields.CaseFieldSettings.Input;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.domain.business.RedactionCatalog;
import com.securedoc.platform.domain.error.LvErrorCode;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0061: LexaVault's rules on top of platform-fields' definitions. */
class CaseFieldSettingsTest {
    private final TenantId tenant = TenantId.generate();
    private final ActorId admin = ActorId.generate();
    private final TestCaseFields fields = new TestCaseFields();
    private final CaseFieldSettings settings = fields.settings();
    private Set<EntityType> redacted = RedactionCatalog.onByDefault();

    private List<FieldDefinition> save(Input... list) {
        return settings.save(tenant, admin, List.of(list), () -> redacted);
    }

    private static Input primary(UUID id) {
        return field(id, "Contract Number", true, "TEXT");
    }

    private static Input field(UUID id, String label, boolean primary, String type) {
        return new Input(id, label, primary, primary, true, false, true, type, null, null, null, "UPPER", false, Set.of(), null);
    }

    private static Input sensitive(UUID id, String label, Boolean sensitive) {
        return new Input(id, label, false, false, true, false, true, "TEXT", null, null, null, "UPPER", false, Set.of(), null,
                "PARTIAL", sensitive, null, null, null, null);
    }

    private LvErrorCode refused(Input... list) {
        return assertThrows(ValidationException.class, () -> save(list)).lvCode();
    }

    @Test
    void exactlyOnePrimaryRequiredAndShown() {
        assertEquals(LvErrorCode.CFG_001, refused());
        assertEquals(LvErrorCode.CFG_001, refused(field(null, "Contract Number", false, "TEXT")));
        assertEquals(LvErrorCode.CFG_001, refused(primary(null), field(null, "Loan", true, "TEXT")));
        assertEquals(LvErrorCode.CFG_001, refused(new Input(null, "Contract", false, true, true, false, true, "TEXT", null, null,
                null, null, false, Set.of(), null)));
        assertEquals(LvErrorCode.CFG_001, refused(primary(null), field(null, " contract number ", false, "TEXT")));
    }

    @Test
    void thePrimaryPlaceMovesInOneSave() {
        var first = save(primary(null), field(null, "Loan reference", false, "TEXT"));
        var moved = save(field(first.get(0).id(), "Contract Number", false, "TEXT"),
                field(first.get(1).id(), "Loan reference", true, "TEXT"));
        assertEquals(List.of(false, true), moved.stream().map(d -> d.spec().primary()).toList());
    }

    @Test
    void aFieldLeftOutIsSwitchedOffNeverDeletedAndCanComeBack() {
        var first = save(primary(null), field(null, "Branch", false, "TEXT"));
        UUID branch = first.get(1).id();
        assertEquals(1, save(primary(first.getFirst().id())).size());
        var all = settings.list(tenant);
        assertEquals(2, all.size());
        assertFalse(all.stream().filter(f -> f.id().equals(branch)).findFirst().orElseThrow().definition().active());
        assertEquals(2, save(primary(first.getFirst().id()), field(branch, "Branch", false, "TEXT")).size());
    }

    @Test
    void theAppsOwnSettingIsKeptByKey() {
        save(primary(null), new Input(null, "Branch", false, false, true, true, true, "TEXT", null, null, null, null, false,
                Set.of(), null));
        assertEquals(Map.of("branch", true, "contract_number", false), settings.list(tenant).stream()
                .collect(java.util.stream.Collectors.toMap(CaseFieldSettings.Field::key, CaseFieldSettings.Field::extractable)));
        assertEquals("true", fields.definitions.extensions(CaseFieldService.scope(tenant),
                com.documentplatform.fields.FieldKey.of("branch")).get("extractable"));
    }

    @Test
    void namesAndAddressesAreSensitiveUnlessTheAdminSaysOtherwiseAndAreFoundOnlyExactly() {
        var saved = save(primary(null), sensitive(null, "Borrower name", null), sensitive(null, "Property address", null),
                sensitive(null, "Agent name", false), sensitive(null, "Branch", null));
        assertEquals(List.of(false, true, true, false, false), saved.stream().map(d -> d.spec().sensitive()).toList());
        assertEquals(SearchMode.EXACT, saved.get(1).spec().searchMode());
        assertEquals(SearchMode.PARTIAL, saved.get(4).spec().searchMode());
    }

    @Test
    void withoutAKeyServiceASensitiveFieldIsRefused() {
        TestCaseFields none = new TestCaseFields(false);
        ValidationException e = assertThrows(ValidationException.class, () -> none.settings().save(tenant, admin,
                List.of(primary(null), sensitive(null, "Borrower name", true)), () -> redacted));
        assertEquals(LvErrorCode.CFG_012, e.lvCode());
        assertFalse(none.settings().save(tenant, admin, List.of(primary(null), sensitive(null, "Borrower name", null)),
                () -> redacted).get(1).spec().sensitive(), "what its name suggests can't be set up here");
    }

    @Test
    void typeSensitiveAndPrimaryStayOnceValuesExistTheNameDoesNot() {
        var saved = save(primary(null), field(null, "Branch", false, "TEXT"));
        fields.service().resolveOrCreate(tenant, Map.of("contract_number", "C-1", "branch", "North"));
        UUID contract = saved.get(0).id();
        UUID branch = saved.get(1).id();
        assertTrue(settings.list(tenant).get(1).locked());
        assertEquals(LvErrorCode.CFG_015, refused(primary(contract), field(branch, "Branch", false, "DATE")));
        assertEquals(LvErrorCode.CFG_015, refused(primary(contract), sensitive(branch, "Branch", true)));
        assertEquals(LvErrorCode.CFG_015, refused(field(contract, "Contract Number", false, "TEXT"),
                field(branch, "Branch", true, "TEXT")));
        assertEquals(LvErrorCode.CFG_015, refused(primary(contract), new Input(branch, "Branch", false, false, true, false, true,
                "TEXT", null, null, null, "UPPER", false, Set.of(), null, "EXACT", false, true, null, null, null)));
        assertEquals("Office", save(primary(contract), field(branch, "Office", false, "TEXT")).get(1).spec().label());
    }

    @ParameterizedTest
    @ValueSource(strings = {"SSN", "Borrower SSN", "borrower_ssn", "Social Security Number", "Credit card number", "Card number",
            "CVV", "Security code", "PIN", "PIN code"})
    void lockedItemsAreNeverACaseField(String label) {
        redacted = Set.of();
        assertEquals(LvErrorCode.CFG_013, refused(primary(null), field(null, label, false, "TEXT")));
    }

    @ParameterizedTest
    @CsvSource({"Date of birth,DATE_OF_BIRTH", "DOB,DATE_OF_BIRTH", "Credit score,CREDIT_SCORE", "Account number,BANK_ACCOUNT_NUMBER",
            "Routing number,BANK_ROUTING_NUMBER", "Borrower name,PERSON_NAME", "Driver's license,DRIVERS_LICENSE_NUMBER",
            "Passport number,PASSPORT_NUMBER", "EIN,EIN", "ITIN,ITIN", "Signature,SIGNATURE"})
    void aCaseFieldOrARedactionItemNeverBoth(String label, EntityType kind) {
        redacted = EnumSet.of(kind);
        assertEquals(LvErrorCode.CFG_014, refused(primary(null), field(null, label, false, "TEXT")));
        redacted = Set.of();
        save(primary(null), field(null, label, false, "TEXT"));
        ValidationException e = assertThrows(ValidationException.class,
                () -> settings.requireNoField(tenant, EnumSet.of(kind), Set.of()));
        assertEquals(LvErrorCode.CFG_014, e.lvCode());
        assertTrue(e.getMessage().contains(label), e.getMessage());
        settings.requireNoField(tenant, EnumSet.complementOf(EnumSet.of(kind)), Set.of());
    }

    @ParameterizedTest
    @ValueSource(strings = {"Contract Number", "Member Number", "Loan number", "Service Entity", "Branch name", "Company name",
            "Policy number", "Property address", "Case PINnacle code", "Pinot"})
    void otherNamesAreFree(String label) {
        redacted = RedactionCatalog.kinds();
        assertEquals(Set.of(), CaseFieldRedactionRule.itemsOf(label, label.toLowerCase().replace(' ', '_')));
    }

    @Test
    void everyTypeCanBeSetUp() {
        List<Input> list = new ArrayList<>(List.of(primary(null)));
        for (FieldType type : FieldType.values()) {
            list.add(new Input(null, "Field " + type, false, false, true, false, true, type.name(), null, null, null, "PRESERVE",
                    false, type == FieldType.DROPDOWN ? Set.of("A", "B") : Set.of(),
                    type == FieldType.CUSTOM_PATTERN ? "[A-Z]+" : null, null, false, true,
                    type == FieldType.MONEY ? "usd" : null, null, null));
        }
        assertEquals(FieldType.values().length + 1, settings.save(tenant, admin, list, () -> redacted).size());
        assertEquals(LvErrorCode.CFG_001, refused(primary(null), field(null, "Money", false, "MONEY")));
        assertEquals(LvErrorCode.CFG_001, refused(primary(null), field(null, "Other", false, "NUMERIC")));
    }

    /** Task 0113 (U10): a dropdown value may have a display name; the code stays what is stored, in the admin's order. */
    @Test
    void dropdownValuesCanHaveNamesAndTheCodesStay() {
        java.util.Set<String> codes = new java.util.LinkedHashSet<>(List.of("000", "001", "002"));
        java.util.Map<String, String> names = new java.util.LinkedHashMap<>();
        names.put("000", "Club");
        names.put("001", " Walmart ");
        Input region = new Input(null, "Region", false, false, true, false, true, "DROPDOWN", null, null, null, "PRESERVE",
                false, codes, null, null, false, false, null, null, null, names);
        FieldDefinition saved = save(primary(null), region).stream()
                .filter(d -> d.spec().label().equals("Region")).findFirst().orElseThrow();
        assertEquals(List.of("000", "001", "002"), saved.spec().options(), "the codes, in order");
        assertEquals("Club", saved.spec().displayName("000"));
        assertEquals("Walmart", saved.spec().displayName("001"));
        assertEquals("002", saved.spec().displayName("002"), "a value without a name shows its code");

        java.util.Map<String, String> twice = Map.of("000", "Club", "001", "Club");
        assertEquals(LvErrorCode.CFG_001, refused(primary(null), new Input(null, "Other region", false, false, true,
                false, true, "DROPDOWN", null, null, null, "PRESERVE", false, codes, null, null, false, false, null, null, null,
                twice)), "a name used twice in one field is refused");
    }

    @Test
    void aFieldOfAnotherEntityIsRefused() {
        UUID other = settings.save(TenantId.generate(), admin, List.of(primary(null)), () -> redacted).getFirst().id();
        assertEquals(LvErrorCode.CFG_001, refused(primary(other)));
    }
}
