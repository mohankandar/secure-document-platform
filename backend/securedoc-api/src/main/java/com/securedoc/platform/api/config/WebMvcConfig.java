package com.securedoc.platform.api.config;

import com.securedoc.platform.api.security.CapabilityAuthorizationInterceptor;
import com.securedoc.platform.api.security.TenantBoundaryInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers central tenant validation before capability authorization for all product API requests. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantBoundaryInterceptor tenantBoundaryInterceptor;
    private final CapabilityAuthorizationInterceptor capabilityAuthorizationInterceptor;

    public WebMvcConfig(TenantBoundaryInterceptor tenantBoundaryInterceptor,
                        CapabilityAuthorizationInterceptor capabilityAuthorizationInterceptor) {
        this.tenantBoundaryInterceptor = tenantBoundaryInterceptor;
        this.capabilityAuthorizationInterceptor = capabilityAuthorizationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantBoundaryInterceptor).addPathPatterns("/api/v1/**");
        registry.addInterceptor(capabilityAuthorizationInterceptor).addPathPatterns("/api/v1/**");
    }
}
