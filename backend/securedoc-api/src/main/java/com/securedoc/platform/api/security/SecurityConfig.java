package com.securedoc.platform.api.security;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.MediaType;
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
                        .authenticationEntryPoint((request, response, ex) -> {
                            response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                            response.setContentType(MediaType.APPLICATION_JSON_VALUE);
                            objectMapper.writeValue(response.getOutputStream(), java.util.Map.of(
                                    "errorCode", "AUTHENTICATION_REQUIRED",
                                    "message", "Authentication is required"));
                        }))
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
        OAuth2TokenValidator<Jwt> audienceValidator = new OidcAudienceValidator(audience);
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(issuerValidator, audienceValidator));
        return decoder;
    }

    @Bean
    CorsConfigurationSource corsConfigurationSource(SecurityProperties properties) {
        CorsConfiguration configuration = new CorsConfiguration();
        String allowed = properties.getCors().getAllowedOrigins();
        if (allowed != null && !allowed.isBlank()) {
            configuration.setAllowedOrigins(Arrays.stream(allowed.split(",")).map(String::trim)
                    .filter(value -> !value.isBlank()).toList());
        } else {
            configuration.setAllowedOrigins(List.of());
        }
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "OPTIONS"));
        configuration.setAllowedHeaders(properties.getMode() == SecurityMode.LOCAL
                ? List.of("Authorization", "Content-Type", "X-Correlation-Id", "X-Tenant-Id", "X-Actor-Id", "X-Actor-Roles")
                : List.of("Authorization", "Content-Type", "X-Correlation-Id"));
        configuration.setExposedHeaders(List.of(
                "X-Correlation-Id",
                "X-Review-Page-Number",
                "X-Review-Image-Width",
                "X-Review-Image-Height"));
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
