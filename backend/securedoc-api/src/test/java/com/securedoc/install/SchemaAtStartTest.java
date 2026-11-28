package com.securedoc.install;

import org.junit.jupiter.api.Test;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.env.PropertySource;
import org.springframework.core.io.ClassPathResource;

import java.io.IOException;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Task 0114: the install proof found that LexaVault started without a profile ran Liquibase (Spring's default), with a
 * changelog path that does not exist, and stopped. An installed LexaVault changes no table at start: the schema step does
 * that. Only the local development start runs it.
 */
class SchemaAtStartTest {

    @Test
    void anInstalledLexaVaultRunsNoSchemaChangeAtStart() throws IOException {
        assertEquals(false, value("application.yml", "spring.liquibase.enabled"));
        assertEquals("classpath:db/changelog/db.changelog-master.xml", value("application.yml", "spring.liquibase.change-log"));
    }

    @Test
    void theLocalDevelopmentStartStillRunsIt() throws IOException {
        assertEquals(true, value("application-local.yml", "spring.liquibase.enabled"));
    }

    private static Object value(String file, String key) throws IOException {
        List<PropertySource<?>> documents = new YamlPropertySourceLoader().load(file, new ClassPathResource(file));
        // The file's first document: the settings without a profile or platform condition.
        return documents.getFirst().getProperty(key);
    }
}
