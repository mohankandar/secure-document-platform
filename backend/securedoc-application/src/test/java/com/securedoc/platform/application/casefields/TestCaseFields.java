package com.securedoc.platform.application.casefields;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import com.documentplatform.crypto.testkit.InMemoryKeyService;
import com.documentplatform.fields.FieldDefinition;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.FieldSpec;
import com.documentplatform.fields.inmemory.InMemoryFieldStore;
import com.documentplatform.fields.inmemory.InMemoryValueStore;
import com.documentplatform.fields.values.FieldValues;
import com.securedoc.platform.application.port.out.CaseFieldLock;
import com.securedoc.platform.domain.identity.TenantId;

import java.time.Clock;

/** platform-fields in memory (its own test kit), with or without a key service. */
public final class TestCaseFields {
    public final InMemoryFieldStore fields = new InMemoryFieldStore();
    public final InMemoryValueStore values = new InMemoryValueStore();
    public final InMemoryKeyService keys = new InMemoryKeyService();
    public final CustomerId customer = CustomerId.fromTenantId("TEN-TEST-0001");
    public final FieldDefinitions definitions = new FieldDefinitions(fields, values);
    private final boolean withKeys;

    public TestCaseFields() {
        this(true);
    }

    public TestCaseFields(boolean withKeys) {
        this.withKeys = withKeys;
        if (withKeys) keys.createKeys(customer);
    }

    public CaseFieldService service() {
        return new CaseFieldService(definitions, new FieldValues(customer, fields, values, keys, Clock.systemUTC()),
                CaseFieldLock.NONE, withKeys ? new Fingerprinter(keys) : null, customer);
    }

    public CaseFieldSettings settings() {
        return new CaseFieldSettings(definitions, values, CaseFieldLock.NONE, withKeys);
    }

    public FieldDefinition add(TenantId tenant, FieldSpec.Builder field) {
        return definitions.create(CaseFieldService.scope(tenant), field.build(), "test");
    }
}
