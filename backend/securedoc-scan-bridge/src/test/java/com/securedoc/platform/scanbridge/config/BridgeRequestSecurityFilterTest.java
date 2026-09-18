package com.securedoc.platform.scanbridge.config;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.securedoc.platform.scanbridge.service.BridgeRuntimeIdentity;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.junit.jupiter.api.Assertions.*;

class BridgeRequestSecurityFilterTest {

    @Test
    void rejectsBrowserOriginThatIsNotExplicitlyAllowed() throws Exception {
        Fixture fixture = fixture();
        MockHttpServletRequest request = request("GET", "/api/v1/scanner-status");
        request.addHeader("Origin", "https://hostile.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        fixture.filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("BRIDGE_ORIGIN_NOT_ALLOWED"));
        assertNull(chain.getRequest());
    }

    @Test
    void rejectsMutationWithoutCurrentPerProcessBrowserToken() throws Exception {
        Fixture fixture = fixture();
        MockHttpServletRequest request = request("POST", "/api/v1/scan-sessions");
        request.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        fixture.filter.doFilter(request, response, chain);

        assertEquals(403, response.getStatus());
        assertTrue(response.getContentAsString().contains("BRIDGE_REQUEST_TOKEN_INVALID"));
        assertEquals("http://localhost:5173", response.getHeader("Access-Control-Allow-Origin"));
        assertNull(chain.getRequest());
    }

    @Test
    void allowsMutationFromTrustedOriginWithCurrentToken() throws Exception {
        Fixture fixture = fixture();
        MockHttpServletRequest request = request("POST", "/api/v1/scan-sessions");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader(BridgeRequestSecurityFilter.REQUEST_TOKEN_HEADER, fixture.runtimeIdentity.browserRequestToken());
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        fixture.filter.doFilter(request, response, chain);

        assertEquals(200, response.getStatus());
        assertSame(request, chain.getRequest());
    }


    @Test
    void answersTrustedCorsPreflightBeforeMvcSoBrowserMutationCanProceed() throws Exception {
        Fixture fixture = fixture();
        MockHttpServletRequest request = request("OPTIONS", "/api/v1/scan-sessions");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Headers", "content-type,x-lexavault-bridge-token");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        fixture.filter.doFilter(request, response, chain);

        assertEquals(204, response.getStatus());
        assertEquals("http://localhost:5173", response.getHeader("Access-Control-Allow-Origin"));
        assertTrue(response.getHeader("Access-Control-Allow-Methods").contains("POST"));
        assertTrue(response.getHeader("Access-Control-Allow-Headers").contains(BridgeRequestSecurityFilter.REQUEST_TOKEN_HEADER));
        assertNull(chain.getRequest());
    }

    @Test
    void answersTrustedPrivateNetworkPreflightWhenBrowserRequestsIt() throws Exception {
        Fixture fixture = fixture();
        MockHttpServletRequest request = request("OPTIONS", "/api/v1/scan-sessions");
        request.addHeader("Origin", "http://localhost:5173");
        request.addHeader("Access-Control-Request-Method", "POST");
        request.addHeader("Access-Control-Request-Private-Network", "true");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        fixture.filter.doFilter(request, response, chain);

        assertEquals(204, response.getStatus());
        assertEquals("true", response.getHeader("Access-Control-Allow-Private-Network"));
        assertNull(chain.getRequest());
    }

    @Test
    void configurationResponseIsMarkedNoStoreBecauseItContainsEphemeralToken() throws Exception {
        Fixture fixture = fixture();
        MockHttpServletRequest request = request("GET", "/api/v1/configuration");
        request.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        fixture.filter.doFilter(request, response, chain);

        assertEquals("no-store, max-age=0", response.getHeader("Cache-Control"));
        assertEquals("no-cache", response.getHeader("Pragma"));
        assertSame(request, chain.getRequest());
    }

    @Test
    void actuatorEndpointsAreOutsideBrowserMutationFilter() throws Exception {
        Fixture fixture = fixture();
        MockHttpServletRequest request = request("GET", "/actuator/health");
        request.addHeader("Origin", "https://hostile.example");
        MockHttpServletResponse response = new MockHttpServletResponse();
        MockFilterChain chain = new MockFilterChain();

        fixture.filter.doFilter(request, response, chain);

        assertSame(request, chain.getRequest());
    }

    private MockHttpServletRequest request(String method, String uri) {
        return new MockHttpServletRequest(method, uri);
    }

    private Fixture fixture() {
        ScanBridgeProperties properties = new ScanBridgeProperties();
        BridgeRuntimeIdentity identity = new BridgeRuntimeIdentity();
        BridgeRequestSecurityFilter filter = new BridgeRequestSecurityFilter(properties, identity, new ObjectMapper());
        return new Fixture(filter, identity);
    }

    private record Fixture(BridgeRequestSecurityFilter filter, BridgeRuntimeIdentity runtimeIdentity) { }
}
