package com.securedoc.platform.scanbridge;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.NONE,
        properties = {
                "securedoc.scan-bridge.enabled=false",
                "securedoc.scan-bridge.simulator.inbox-path=target/scan-bridge-context-test/inbox",
                "securedoc.scan-bridge.work-path=target/scan-bridge-context-test/work",
                "securedoc.scan-bridge.problem-path=target/scan-bridge-context-test/problem"
        })
class ScanBridgeApplicationContextTest {

    @Test
    void applicationContextStartsWithProductionConstructorWiring() {
        // Context startup is the assertion. This guards against constructor-wiring regressions
        // that pure unit tests do not catch.
    }
}
