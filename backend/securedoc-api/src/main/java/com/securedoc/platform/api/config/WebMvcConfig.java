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

    private final com.securedoc.platform.api.intake.IntakeSpaceInterceptor intakeSpaceInterceptor;
    private final com.securedoc.platform.api.protection.WorkGateInterceptor workGateInterceptor;

    public WebMvcConfig(TenantBoundaryInterceptor tenantBoundaryInterceptor,
                        CapabilityAuthorizationInterceptor capabilityAuthorizationInterceptor,
                        com.securedoc.platform.api.intake.IntakeSpaceInterceptor intakeSpaceInterceptor,
                        com.securedoc.platform.api.protection.WorkGateInterceptor workGateInterceptor) {
        this.workGateInterceptor = workGateInterceptor;
        this.intakeSpaceInterceptor = intakeSpaceInterceptor;
        this.tenantBoundaryInterceptor = tenantBoundaryInterceptor;
        this.capabilityAuthorizationInterceptor = capabilityAuthorizationInterceptor;
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // Task 0060: nothing is changed before the erase replay after a restore.
        registry.addInterceptor(workGateInterceptor).addPathPatterns("/**");
        registry.addInterceptor(tenantBoundaryInterceptor).addPathPatterns("/api/v1/**");
        registry.addInterceptor(capabilityAuthorizationInterceptor).addPathPatterns("/api/v1/**");
        registry.addInterceptor(intakeSpaceInterceptor)
                .addPathPatterns(com.securedoc.platform.api.intake.IntakeSpaceInterceptor.PATHS);
    }
}
