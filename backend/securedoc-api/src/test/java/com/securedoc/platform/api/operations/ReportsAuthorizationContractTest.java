package com.securedoc.platform.api.operations;

import com.securedoc.platform.api.controller.OperationsController;
import com.securedoc.platform.api.security.RequireCapability;
import com.securedoc.platform.api.security.UserCapability;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.Arrays;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class ReportsAuthorizationContractTest {

    @Test
    void reportReadAndExportEndpointsHaveSeparateBackendCapabilities() {
        assertArrayEquals(new UserCapability[]{UserCapability.REPORT_EXECUTIVE_VIEW}, capabilityFor("reports"));
        assertArrayEquals(new UserCapability[]{UserCapability.REPORT_EXPORT}, capabilityFor("exportReports"));
    }

    private UserCapability[] capabilityFor(String methodName) {
        Method method = Arrays.stream(OperationsController.class.getDeclaredMethods())
                .filter(candidate -> candidate.getName().equals(methodName))
                .findFirst()
                .orElseThrow();
        return method.getAnnotation(RequireCapability.class).value();
    }
}
