package com.securedoc.platform.domain.business;

import com.securedoc.platform.domain.identity.TenantId;
import org.junit.jupiter.api.Test;

import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;

class BusinessIdentifierValidatorTest {

    @Test
    void numericExactLengthPreservesLeadingZeroes() {
        var definition = definition("contract_number", "Contract Number", BusinessIdentifierValidationType.NUMERIC,
                12, false, Set.of(), null);

        BusinessIdentifier value = BusinessIdentifier.from(definition, "000642500012");

        assertEquals("000642500012", value.value());
        assertEquals("000642500012", value.normalizedValue());
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifier.from(definition, "642500012"));
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifier.from(definition, "00064250001A"));
    }

    @Test
    void numericAllowListEnforcesServiceEntityValues() {
        var definition = definition("service_entity", "Service Entity", BusinessIdentifierValidationType.NUMERIC,
                3, false, Set.of("000", "001", "002", "003", "004", "005"), null);

        assertEquals("005", BusinessIdentifier.from(definition, "005").normalizedValue());
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifier.from(definition, "006"));
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifier.from(definition, "ABC"));
    }

    @Test
    void optionalSeparatorRemovalChangesCanonicalValueButKeepsDisplayValue() {
        var definition = BusinessIdentifierDefinition.create(TenantId.generate(), "reference", "Reference",
                true, true, true, false, true, 1, BusinessIdentifierValidationType.ALPHANUMERIC,
                6, null, null, BusinessIdentifierCaseNormalization.UPPER, true, Set.of(), null);

        BusinessIdentifier value = BusinessIdentifier.from(definition, "ab-12 34");

        assertEquals("ab-12 34", value.value());
        assertEquals("AB1234", value.normalizedValue());
    }

    @Test
    void customPatternIsFullMatch() {
        var definition = definition("code", "Code", BusinessIdentifierValidationType.CUSTOM_PATTERN,
                null, false, Set.of(), "[A-Z]{2}[0-9]{4}");

        assertEquals("AB1234", BusinessIdentifier.from(definition, "AB1234").normalizedValue());
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifier.from(definition, "XAB1234Y"));
    }


    @Test
    void genericValidatorFamiliesAreSupported() {
        var alpha = definition("alpha", "Alpha", BusinessIdentifierValidationType.ALPHANUMERIC,
                null, false, Set.of(), null);
        assertEquals("AB123", BusinessIdentifier.from(alpha, "ab123").normalizedValue());
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifier.from(alpha, "AB-123"));

        var email = definition("email", "Email", BusinessIdentifierValidationType.EMAIL,
                null, false, Set.of(), null);
        assertEquals("USER@EXAMPLE.COM", BusinessIdentifier.from(email, "user@example.com").normalizedValue());
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifier.from(email, "not-an-email"));

        var uuid = definition("uuid", "UUID", BusinessIdentifierValidationType.UUID,
                null, false, Set.of(), null);
        String uuidValue = "00000000-0000-0000-0000-000000000001";
        assertEquals(uuidValue.toUpperCase(), BusinessIdentifier.from(uuid, uuidValue).normalizedValue());
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifier.from(uuid, "1234"));

        var allowList = definition("region", "Region", BusinessIdentifierValidationType.ALLOW_LIST,
                null, false, Set.of("EAST", "WEST"), null);
        assertEquals("EAST", BusinessIdentifier.from(allowList, "east").normalizedValue());
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifier.from(allowList, "north"));
    }

    @Test
    void invalidValidationConfigurationIsRejected() {
        TenantId tenant = TenantId.generate();
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifierDefinition.create(tenant,
                "bad", "Bad", true, true, true, false, true, 1,
                BusinessIdentifierValidationType.TEXT, 12, 3, null,
                BusinessIdentifierCaseNormalization.UPPER, false, Set.of(), null));
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifierDefinition.create(tenant,
                "bad_regex", "Bad Regex", true, true, true, false, true, 1,
                BusinessIdentifierValidationType.CUSTOM_PATTERN, null, null, null,
                BusinessIdentifierCaseNormalization.UPPER, false, Set.of(), "["));
        assertThrows(IllegalArgumentException.class, () -> BusinessIdentifierDefinition.create(tenant,
                "bad_enum", "Bad Enum", true, true, true, false, true, 1,
                BusinessIdentifierValidationType.ALLOW_LIST, null, null, null,
                BusinessIdentifierCaseNormalization.UPPER, false, Set.of(), null));
    }

    private BusinessIdentifierDefinition definition(String key, String label,
                                                     BusinessIdentifierValidationType type, Integer exactLength,
                                                     boolean stripSeparators, Set<String> allowedValues,
                                                     String pattern) {
        return BusinessIdentifierDefinition.create(TenantId.generate(), key, label, true, true, true, false,
                true, 1, type, exactLength, null, null, BusinessIdentifierCaseNormalization.UPPER,
                stripSeparators, allowedValues, pattern);
    }
}
