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

    /** Task 0025: both refusals carry errorCode as before, the LV code and its title as message. */
    @Test
    void refusalBodiesCarryTheLvCodeAndTitle() throws Exception {
        ObjectMapper mapper = new ObjectMapper();
        Fixture fixture = fixture();

        MockHttpServletRequest hostile = request("GET", "/api/v1/scanner-status");
        hostile.addHeader("Origin", "https://hostile.example");
        MockHttpServletResponse originRefused = new MockHttpServletResponse();
        fixture.filter.doFilter(hostile, originRefused, new MockFilterChain());
        var origin = mapper.readTree(originRefused.getContentAsString());
        assertEquals("BRIDGE_ORIGIN_NOT_ALLOWED", origin.get("errorCode").asText());
        assertEquals("LV-SCN-012", origin.get("code").asText());
        assertEquals("Scan Bridge refused the request", origin.get("message").asText());

        MockHttpServletRequest noToken = request("POST", "/api/v1/scan-sessions");
        noToken.addHeader("Origin", "http://localhost:5173");
        MockHttpServletResponse tokenRefused = new MockHttpServletResponse();
        fixture.filter.doFilter(noToken, tokenRefused, new MockFilterChain());
        var token = mapper.readTree(tokenRefused.getContentAsString());
        assertEquals(403, tokenRefused.getStatus());
        assertEquals("BRIDGE_REQUEST_TOKEN_INVALID", token.get("errorCode").asText());
        assertEquals("LV-SCN-012", token.get("code").asText());
        assertEquals("Scan Bridge refused the request", token.get("message").asText());
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
