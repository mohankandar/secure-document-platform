package com.securedoc.platform.api.command;

import org.springframework.beans.BeansException;
import org.springframework.beans.factory.annotation.AnnotatedBeanDefinition;
import org.springframework.beans.factory.config.BeanDefinition;
import org.springframework.beans.factory.config.ConfigurableListableBeanFactory;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.core.type.MethodMetadata;
import org.springframework.util.ClassUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * Command mode only (never registered for the server). Leaves out of the command's Spring context:
 * <ul>
 *   <li>the product's start-up work: every {@link ApplicationRunner}, {@link CommandLineRunner} and
 *       {@link ApplicationListener} in the product's packages, such as the demo data bootstrap, the login-system
 *       bootstrap and recovery, and the storage bootstrap;</li>
 *   <li>the web security filter chains ({@code SecurityFilterChain} beans), which need the web server.</li>
 * </ul>
 * No product class is changed for this, and the server's context is not affected. A command does its one job only.
 */
final class CommandModeBeanFilter implements BeanDefinitionRegistryPostProcessor {

    static final String PRODUCT_PACKAGE = "com.securedoc.platform.";
    static final String SECURITY_FILTER_CHAIN = "org.springframework.security.web.SecurityFilterChain";
    private final List<String> removed = new ArrayList<>();

    @Override
    public void postProcessBeanDefinitionRegistry(BeanDefinitionRegistry registry) throws BeansException {
        ClassLoader loader = ClassUtils.getDefaultClassLoader();
        for (String name : registry.getBeanDefinitionNames()) {
            BeanDefinition definition = registry.getBeanDefinition(name);
            if (isSecurityFilterChain(definition) || isStartupWork(definition, loader)) {
                registry.removeBeanDefinition(name);
                removed.add(name);
            }
        }
    }

    private static boolean isSecurityFilterChain(BeanDefinition definition) {
        if (!(definition instanceof AnnotatedBeanDefinition annotated)) return false;
        MethodMetadata factory = annotated.getFactoryMethodMetadata();
        return factory != null && SECURITY_FILTER_CHAIN.equals(factory.getReturnTypeName());
    }

    private static boolean isStartupWork(BeanDefinition definition, ClassLoader loader) {
        String className = definition.getBeanClassName();
        if (className == null || !className.startsWith(PRODUCT_PACKAGE)) return false;
        Class<?> type = ClassUtils.resolveClassName(className, loader);
        return ApplicationRunner.class.isAssignableFrom(type) || CommandLineRunner.class.isAssignableFrom(type)
                || ApplicationListener.class.isAssignableFrom(type);
    }

    @Override
    public void postProcessBeanFactory(ConfigurableListableBeanFactory beanFactory) {
    }

    List<String> removed() {
        return List.copyOf(removed);
    }
}
