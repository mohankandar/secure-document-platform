package com.securedoc.platform.api.security;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2AuthenticationException;
import org.springframework.security.oauth2.server.resource.BearerTokenErrors;
import org.springframework.security.oauth2.server.resource.web.BearerTokenResolver;
import org.springframework.security.oauth2.server.resource.web.DefaultBearerTokenResolver;

/**
 * Task 0056: the token comes from the Authorization header only, and a GET or a form request that also carries an
 * {@code access_token} parameter is refused, as before Spring Security 7 (which no longer looks at the parameter).
 */
final class OneBearerTokenResolver implements BearerTokenResolver {

    private final BearerTokenResolver header = new DefaultBearerTokenResolver();

    @Override
    public String resolve(HttpServletRequest request) {
        String token = header.resolve(request);
        if (HttpMethod.GET.matches(request.getMethod())
                || MediaType.APPLICATION_FORM_URLENCODED_VALUE.equals(request.getContentType())) {
            String[] parameters = request.getParameterValues("access_token");
            int others = parameters == null ? 0 : parameters.length;
            if (others > 1 || (others == 1 && token != null)) {
                throw new OAuth2AuthenticationException(
                        BearerTokenErrors.invalidRequest("Found multiple bearer tokens in the request"));
            }
        }
        return token;
    }
}
