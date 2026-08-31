package com.securedoc.platform.api.security;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to declare required roles for an API endpoint method.
 * The actor must have at least one of the specified roles.
 * ADMIN always has access regardless of annotation values.
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RequireRole {
    /**
     * Roles that are allowed access. Actor must have at least one.
     */
    String[] value();
}
