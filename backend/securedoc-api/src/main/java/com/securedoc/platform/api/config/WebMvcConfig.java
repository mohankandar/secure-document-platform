package com.securedoc.platform.api.config;

import com.securedoc.platform.api.security.RoleAuthorizationInterceptor;
import com.securedoc.platform.api.security.TenantBoundaryInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/** Registers central tenant validation before role authorization for all product API requests. */
@Configuration
public class WebMvcConfig implements WebMvcConfigurer {

    private final TenantBoundaryInterceptor tenantBoundaryInterceptor;
    private final RoleAuthorizationInterceptor roleAuthorizationInterceptor;

    public WebMvcConfig(TenantBoundaryInterceptor tenantBoundaryInterceptor,
                        RoleAuthorizationInterceptor roleAuthorizationInterceptor) {
        this.tenantBoundaryInterceptor = tenantBoundaryInterceptor;
        this.roleAuthorizationInterceptor = roleAuthorizationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(tenantBoundaryInterceptor).addPathPatterns("/api/v1/**");
        registry.addInterceptor(roleAuthorizationInterceptor).addPathPatterns("/api/v1/**");
    }
}
