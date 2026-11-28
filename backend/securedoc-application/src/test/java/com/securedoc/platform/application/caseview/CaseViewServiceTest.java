package com.securedoc.platform.application.caseview;

import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.FieldType;
import com.securedoc.platform.application.casefields.TestCaseFields;
import com.securedoc.platform.application.exception.ValidationException;
import com.securedoc.platform.application.port.out.CaseViewStore;
import com.securedoc.platform.domain.identity.ActorId;
import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Task 0108: the View menu offers only what the entity has, refuses anything else whole, and reads back safely. */
class CaseViewServiceTest {
    private final TenantId tenant = TenantId.generate();
    private final ActorId person = ActorId.generate();
    private final Map<String, CaseViewSettings> saved = new HashMap<>();
    private TestCaseFields fields;
    private CaseViewService service;

    @BeforeEach
    void setUp() {
        fields = new TestCaseFields();
        fields.add(tenant, FieldSpec.builder("contract_number", "Contract Number", FieldType.NUMBER).pattern("[0-9]+")
                .required(true).primary(true).searchable(true).displayable(true).displayOrder(1));
        fields.add(tenant, FieldSpec.builder("borrower", "Borrower", FieldType.TEXT).displayable(true).displayOrder(3));
        fields.add(tenant, FieldSpec.builder("branch", "Branch", FieldType.TEXT).displayable(true).displayOrder(2));
        fields.add(tenant, FieldSpec.builder("internal_note", "Internal note", FieldType.TEXT).displayable(false)
                .displayOrder(4));
        CaseViewStore store = new CaseViewStore() {
            public Optional<CaseViewSettings> find(TenantId t, ActorId a) { return Optional.ofNullable(saved.get(t + "/" + a)); }
            public void save(TenantId t, ActorId a, CaseViewSettings s) { saved.put(t + "/" + a, s); }
        };
        service = new CaseViewService(store, fields.settings()::active);
    }

    @Test
    void defaultIsReceivedOldestFirstComfortableWithDefaultColumns() {
        var view = service.read(tenant, person);
        assertEquals(new CaseViewSettings("received", "asc", null, "comfortable"), view.settings());
        assertEquals(List.of("received", "waitingLongest", "priority", "field:contract_number"), view.options().sorts());
        // Displayable fields only, in the admin's order, then the built-in columns.
        assertEquals(List.of("contract_number", "branch", "borrower", "received", "uploaded", "source"),
                view.options().columns());
    }

    @Test
    void savedChoicesComeBackInTheAdminsOrder() {
        var view = service.save(tenant, person, new CaseViewSettings("field:contract_number", "desc",
                List.of("source", "borrower", "branch"), "compact"));
        assertEquals(new CaseViewSettings("field:contract_number", "desc", List.of("branch", "borrower", "source"),
                "compact"), view.settings());
        assertEquals(view.settings(), service.read(tenant, person).settings());
        assertEquals(CaseViewSettings.DEFAULT, service.read(tenant, ActorId.generate()).settings());
    }

    @Test
    void anythingTheEntityDoesNotOfferIsRefusedWhole() {
        List<CaseViewSettings> refused = List.of(
                new CaseViewSettings("field:borrower", "asc", null, "compact"),
                new CaseViewSettings("anything", "asc", null, "compact"),
                new CaseViewSettings(null, "asc", null, "compact"),
                new CaseViewSettings("received", "up", null, "compact"),
                new CaseViewSettings("received", "asc", null, "tiny"),
                new CaseViewSettings("received", "asc", List.of("internal_note"), "compact"),
                new CaseViewSettings("received", "asc", List.of("branch", "branch"), "compact"),
                new CaseViewSettings("received", "asc", java.util.Arrays.asList("branch", null), "compact"));
        for (CaseViewSettings settings : refused) {
            assertThrows(ValidationException.class, () -> service.save(tenant, person, settings), settings.toString());
        }
        assertThrows(ValidationException.class, () -> service.save(tenant, person, null));
        assertTrue(saved.isEmpty());
    }

    @Test
    void emptyColumnsMeansNoOptionalColumnNotTheDefault() {
        var view = service.save(tenant, person, new CaseViewSettings("priority", "asc", List.of(), "comfortable"));
        assertEquals(List.of(), view.settings().columns());
    }

    @Test
    void readLeavesOutWhatTheAdminHasSinceSwitchedOff() {
        saved.put(tenant + "/" + person, new CaseViewSettings("field:old_primary", "sideways",
                List.of("branch", "gone_field"), "huge"));
        CaseViewSettings read = service.read(tenant, person).settings();
        assertEquals(new CaseViewSettings("received", "asc", List.of("branch"), "comfortable"), read);
        saved.put(tenant + "/" + person, new CaseViewSettings("received", "asc", null, "compact"));
        assertNull(service.read(tenant, person).settings().columns());
    }

    @Test
    void anotherEntityHasItsOwnChoices() {
        service.save(tenant, person, new CaseViewSettings("priority", "desc", null, "compact"));
        TenantId other = TenantId.generate();
        // The other entity has no case fields: only the built-in sorts and columns.
        var view = service.read(other, person);
        assertEquals(CaseViewSettings.DEFAULT, view.settings());
        assertEquals(List.of("received", "uploaded", "source"), view.options().columns());
    }
}
