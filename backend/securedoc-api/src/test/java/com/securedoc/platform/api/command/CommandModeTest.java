package com.securedoc.platform.api.command;

import com.securedoc.platform.api.config.SchedulingConfiguration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.beans.factory.support.RootBeanDefinition;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationListener;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.config.TaskManagementConfigUtils;
import org.springframework.security.web.DefaultSecurityFilterChain;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.util.matcher.AnyRequestMatcher;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CommandModeTest {

    @Test
    void commandModeIsOnlyForTheCommandOption() {
        assertEquals(Optional.of("load-role-mappings"), CommandMode.requested(new String[] {"--file=x", "--lexavault.command=load-role-mappings"}));
        assertEquals(Optional.empty(), CommandMode.requested(new String[] {"--server.port=8080"}));
        assertEquals(Optional.empty(), CommandMode.requested(new String[] {}));
    }

    @Test
    void theCommandAlwaysRunsWithoutWebServerMigrationsOrStartupWork() {
        List<String> args = Arrays.asList(CommandMode.contextArguments(new String[] {"--lexavault.command=x"}).orElseThrow());
        assertTrue(args.contains("--spring.main.web-application-type=none"));
        assertTrue(args.contains("--spring.liquibase.enabled=false"));
        assertTrue(args.contains("--lexavault.command-mode=true"));
        assertTrue(args.contains("--securedoc.ai.ollama-preload-enabled=false"));
        assertTrue(args.contains("--securedoc.security.oidc.recovery.enabled=false"));
        assertTrue(args.contains("--logging.level.root=WARN"));
    }

    @Test
    void aFixedSettingCannotBeOverriddenButADefaultCan() {
        assertEquals(Optional.empty(), CommandMode.contextArguments(new String[] {"--spring.liquibase.enabled=true"}));
        assertEquals(Optional.empty(), CommandMode.contextArguments(new String[] {"--spring.main.web-application-type=servlet"}));
        List<String> args = Arrays.asList(CommandMode.contextArguments(new String[] {"--logging.level.root=INFO"}).orElseThrow());
        assertEquals(1, args.stream().filter(a -> a.startsWith("--logging.level.root=")).count());
    }

    @Test
    void refusedBeforeSpringStarts() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        PrintStream out = new PrintStream(bytes, true, StandardCharsets.UTF_8);
        assertEquals(ProductCommand.USAGE, CommandMode.run(Object.class,
                new String[] {"--lexavault.command=load-role-mappings", "--spring.liquibase.enabled=true"}, out));
        assertEquals(ProductCommand.USAGE, CommandMode.run(Object.class,
                new String[] {"--lexavault.command=a", "--lexavault.command=b"}, out));
        assertTrue(bytes.toString(StandardCharsets.UTF_8).contains("fixed in command mode"));
    }

    // ---- the bean filter

    static class ProductRunner implements ApplicationRunner {
        @Override public void run(ApplicationArguments args) { }
    }

    static class ProductReadyListener implements ApplicationListener<ApplicationReadyEvent> {
        @Override public void onApplicationEvent(ApplicationReadyEvent event) { }
    }

    static class OrdinaryBean { }

    @Configuration
    static class WebSecurity {
        @Bean SecurityFilterChain chain() { return new DefaultSecurityFilterChain(AnyRequestMatcher.INSTANCE); }
        @Bean OrdinaryBean ordinary() { return new OrdinaryBean(); }
    }

    @Test
    void theFilterRemovesStartupWorkAndSecurityChainsOnly() {
        CommandModeBeanFilter filter = new CommandModeBeanFilter();
        try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
            context.registerBeanDefinition("filter", new RootBeanDefinition(CommandModeBeanFilter.class, () -> filter));
            context.registerBean("runner", ProductRunner.class);
            context.registerBean("listener", ProductReadyListener.class);
            context.registerBean("plain", OrdinaryBean.class);
            context.register(WebSecurity.class);
            context.refresh();
            assertFalse(context.containsBean("runner"));
            assertFalse(context.containsBean("listener"));
            assertFalse(context.containsBean("chain"));
            assertTrue(context.containsBean("plain"));
            assertTrue(context.containsBean("ordinary"));
        }
        assertEquals(java.util.Set.of("runner", "listener", "chain"), java.util.Set.copyOf(filter.removed()));
    }

    @Test
    void theFilterLeavesFrameworkBeansAlone() {
        DefaultListableBeanFactory registry = new DefaultListableBeanFactory();
        registry.registerBeanDefinition("frameworkListener", new RootBeanDefinition(
                org.springframework.context.event.SimpleApplicationEventMulticaster.class));
        new CommandModeBeanFilter().postProcessBeanDefinitionRegistry(registry);
        assertTrue(registry.containsBeanDefinition("frameworkListener"));
    }

    // ---- scheduling

    @Test
    void scheduledJobsRunInTheServerButNotInCommandMode() {
        ApplicationContextRunner runner = new ApplicationContextRunner().withUserConfiguration(SchedulingConfiguration.class);
        runner.run(server -> assertTrue(server.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)));
        runner.withPropertyValues("lexavault.command-mode=true").run(command ->
                assertFalse(command.containsBean(TaskManagementConfigUtils.SCHEDULED_ANNOTATION_PROCESSOR_BEAN_NAME)));
    }
}
