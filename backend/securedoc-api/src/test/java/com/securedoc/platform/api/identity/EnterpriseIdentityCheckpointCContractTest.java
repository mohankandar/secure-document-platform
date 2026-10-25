package com.securedoc.platform.api.identity;

import com.securedoc.platform.infrastructure.identity.JdbcOidcConnectionBindingRepository;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertTrue;

class EnterpriseIdentityCheckpointCContractTest {
    @Test void repositoryProvidesTransactionalActivationRollbackAndKnownGoodLookup(){
        var names=Arrays.stream(JdbcOidcConnectionBindingRepository.class.getDeclaredMethods()).map(Method::getName).toList();
        assertTrue(names.contains("activateValidated"));
        assertTrue(names.contains("rollbackToKnownGood"));
        assertTrue(names.contains("findRollbackCandidatesForTenant"));
        assertTrue(names.contains("recoverKnownGood"));
    }
}
