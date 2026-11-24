package com.securedoc.platform.api.security;

import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.mock.web.MockServletContext;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.jwt.BadJwtException;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.web.FilterChainProxy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.context.support.AnnotationConfigWebApplicationContext;
import org.springframework.web.cors.CorsConfigurationSource;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** Task 0056: what Spring Security 7 would change by itself in the OIDC filter chain, and LexaVault keeps as it was. */
class OidcFilterChainTest {

    @Configuration
    @EnableWebSecurity
    static class Chain {
        @Bean
        JwtDecoder jwtDecoder() {
            return token -> {
                throw new BadJwtException("bad");
            };
        }

        @Bean
        CorsConfigurationSource corsConfigurationSource() {
            return new SecurityConfig().corsConfigurationSource(new SecurityProperties());
        }

        @Bean
        SecurityFilterChain chain(HttpSecurity http) throws Exception {
            return new SecurityConfig().oidcSecurityFilterChain(http, JsonMapper.builderWithJackson2Defaults().build());
        }
    }

    @Test
    void theProtectedResourceMetadataAddressIsNotThere() throws Exception {
        try (AnnotationConfigWebApplicationContext context = new AnnotationConfigWebApplicationContext()) {
            context.setServletContext(new MockServletContext());
            context.register(Chain.class);
            context.refresh();
            List<String> filters = context.getBean(FilterChainProxy.class).getFilterChains().getFirst().getFilters().stream()
                    .map(filter -> filter.getClass().getSimpleName()).toList();
            assertEquals(List.of("BearerTokenAuthenticationFilter"), filters.stream()
                    .filter(name -> name.contains("BearerToken") || name.contains("Metadata") || name.contains("DPoP")
                            || name.contains("Login") || name.contains("Basic")).toList(), filters.toString());

            MockFilterChain reached = new MockFilterChain();
            MockHttpServletRequest request = new MockHttpServletRequest("GET", "/.well-known/oauth-protected-resource");
            request.setServletPath("/.well-known/oauth-protected-resource");
            MockHttpServletResponse response = new MockHttpServletResponse();
            context.getBean(FilterChainProxy.class).doFilter(request, response, reached);
            assertEquals("", response.getContentAsString(), "nothing answers before the application");
            assertEquals(request.getServletPath(), ((jakarta.servlet.http.HttpServletRequest) reached.getRequest()).getServletPath());
        }
    }

    @Test
    void theTokenIsReadFromTheHeaderOnly() {
        OneBearerTokenResolver resolver = new OneBearerTokenResolver();
        assertNull(resolver.resolve(request("GET", null, "t")));
        assertNull(resolver.resolve(request("GET", null)));
        assertEquals("h", resolver.resolve(request("GET", "Bearer h")));
        assertEquals("h", resolver.resolve(request("POST", "Bearer h", "t")), "a JSON request's query is not a token");
    }

    @Test
    void aSecondTokenInAParameterIsRefused() {
        OneBearerTokenResolver resolver = new OneBearerTokenResolver();
        assertThrows(OAuth2AuthenticationException.class, () -> resolver.resolve(request("GET", "Bearer h", "t")));
        assertThrows(OAuth2AuthenticationException.class, () -> resolver.resolve(request("GET", null, "t", "u")));
        MockHttpServletRequest form = request("POST", "Bearer h", "t");
        form.setContentType("application/x-www-form-urlencoded");
        assertThrows(OAuth2AuthenticationException.class, () -> resolver.resolve(form));
    }

    private static MockHttpServletRequest request(String method, String authorization, String... accessTokens) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, "/api/v1/me");
        if (authorization != null) request.addHeader("Authorization", authorization);
        if (accessTokens.length > 0) request.setParameter("access_token", accessTokens);
        return request;
    }
}
