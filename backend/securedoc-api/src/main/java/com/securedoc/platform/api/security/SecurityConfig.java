package com.securedoc.platform.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.documentplatform.identity.IdentityClaimConfig;
import com.documentplatform.identity.spring.OidcJwtIdentityNormalizer;
import com.documentplatform.identity.spring.RequiredAudienceValidator;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.Arrays;
import java.util.List;

@Configuration
public class SecurityConfig {

    private static final org.slf4j.Logger CORS_LOG = org.slf4j.LoggerFactory.getLogger("com.securedoc.platform.api.security.Cors");
    /** What a browser sends as Origin: scheme, host and port only. Anything else is not logged. */
    private static final java.util.regex.Pattern SAFE_ORIGIN =
            java.util.regex.Pattern.compile("https?://[A-Za-z0-9.\\-\\[\\]:]{1,253}");

    /**
     * Task 0023 C7 (#327): a refused origin is logged ({@code CORS_ORIGIN_REJECTED origin=<scheme://host:port>}),
     * so IT can see why a browser call fails with "Invalid CORS request". The origin is logged only when it has that
     * plain form; otherwise {@code origin=unrecognised}.
     */
    static final class LoggingCorsConfiguration extends CorsConfiguration {
        @Override
        public String checkOrigin(String requestOrigin) {
            String accepted = super.checkOrigin(requestOrigin);
            if (accepted == null && requestOrigin != null) {
                String origin = SAFE_ORIGIN.matcher(requestOrigin).matches() ? requestOrigin : "unrecognised";
                CORS_LOG.warn("CORS_ORIGIN_REJECTED origin={} allowedOrigins={}", origin,
                        getAllowedOrigins() == null ? 0 : getAllowedOrigins().size());
            }
            return accepted;
        }
    }

    @Bean
    @ConditionalOnProperty(name = "securedoc.security.mode", havingValue = "LOCAL")
    SecurityFilterChain localSecurityFilterChain(HttpSecurity http) throws Exception {
        return commonHeaders(http)
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "securedoc.security.mode", havingValue = "OIDC", matchIfMissing = true)
    SecurityFilterChain oidcSecurityFilterChain(HttpSecurity http, ObjectMapper objectMapper) throws Exception {
        return commonHeaders(http)
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        .requestMatchers("/actuator/health", "/actuator/health/**").permitAll()
                        .requestMatchers("/api/v1/**").authenticated()
                        .anyRequest().permitAll())
                .oauth2ResourceServer(resource -> resource
                        .jwt(Customizer.withDefaults())
                        // Task 0023 C5 (#252): same 401 code and text, plus correlationId; the reason is logged.
                        .authenticationEntryPoint(new BearerTokenRefusalEntryPoint(objectMapper)))
                .build();
    }

    @Bean
    @ConditionalOnProperty(name = "securedoc.security.mode", havingValue = "OIDC", matchIfMissing = true)
    JwtDecoder jwtDecoder(SecurityProperties properties) {
        String issuer = properties.getOidc().getIssuerUri();
        String audience = properties.getOidc().getAudience();
        if (issuer == null || issuer.isBlank() || audience == null || audience.isBlank()) {
            throw new IllegalStateException("OIDC mode requires securedoc.security.oidc.issuer-uri and audience");
        }
        NimbusJwtDecoder decoder = NimbusJwtDecoder.withIssuerLocation(issuer).build();
        OAuth2TokenValidator<Jwt> issuerValidator = JwtValidators.createDefaultWithIssuer(issuer);
        OAuth2TokenValidator<Jwt> audienceValidator = new RequiredAudienceValidator(audience);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return decoder;
    }


    @Bean
    OidcJwtIdentityNormalizer oidcJwtIdentityNormalizer(SecurityProperties properties) {
        var oidc = properties.getOidc();
        return new OidcJwtIdentityNormalizer(
                oidc.getProviderId(),
                new IdentityClaimConfig(
                        oidc.getUsernameClaim(),
                        oidc.getEmailClaim(),
                        oidc.getDisplayNameClaim(),
                        oidc.getRolesClaim()));
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration configuration = new LoggingCorsConfiguration();
        String allowed = properties.getCors().getAllowedOrigins();
        if (allowed != null && !allowed.isBlank()) {
            configuration.setAllowedOrigins(Arrays.stream(allowed.split(",")).map(String::trim)
                    .filter(value -> !value.isBlank()).toList());
        } else {
            configuration.setAllowedOrigins(List.of());
            // Task 0023 C7 (#327): a browser sign-in cannot work from another origin; say so at start-up.
            if (properties.getMode() == SecurityMode.OIDC) {
                CORS_LOG.warn("CORS_ALLOWED_ORIGINS_EMPTY mode=OIDC: browser calls from another origin will be "
                        + "refused (securedoc.security.cors.allowed-origins)");
            }
        }
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(properties.getMode() == SecurityMode.LOCAL
                ? List.of("Authorization", "Content-Type", "X-Correlation-Id", "X-Tenant-Id", "X-Actor-Id", "X-Actor-Roles", "X-Integration-Client", "Idempotency-Key", "X-Content-SHA256")
                : List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        configuration.setExposedHeaders(List.of(
                "X-Correlation-Id",
                "X-Review-Page-Number",
                "X-Review-Image-Width",
                "X-Review-Image-Height",
                "X-Report-File-SHA256"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);
        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }

    private HttpSecurity commonHeaders(HttpSecurity http) throws Exception {
        return http.headers(headers -> headers
                .contentTypeOptions(Customizer.withDefaults())
                .frameOptions(frame -> frame.deny())
                .referrerPolicy(referrer -> referrer.policy(
                        org.springframework.security.web.header.writers.ReferrerPolicyHeaderWriter.ReferrerPolicy.NO_REFERRER))
                .contentSecurityPolicy(csp -> csp.policyDirectives(
                        "default-src 'self'; frame-ancestors 'none'; object-src 'none'; base-uri 'self'"))
                .httpStrictTransportSecurity(hsts -> hsts
                        .includeSubDomains(true)
                        .maxAgeInSeconds(31536000)));
    }
}
