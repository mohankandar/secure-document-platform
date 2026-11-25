package com.securedoc.platform.api.config;

import com.documentplatform.crypto.CustomerId;
import com.documentplatform.crypto.KeyService;
import com.documentplatform.crypto.fingerprint.Fingerprinter;
import com.documentplatform.eraselog.EraseHandler;
import com.documentplatform.eraselog.fields.CaseFieldsEraseHandler;
import com.documentplatform.fields.FieldDefinitions;
import com.documentplatform.fields.jdbc.FieldSchema;
import com.documentplatform.fields.jdbc.PostgresFieldStore;
import com.documentplatform.fields.jdbc.PostgresValueStore;
import com.documentplatform.fields.values.FieldValues;
import com.securedoc.platform.application.casefields.CaseFieldService;
import com.securedoc.platform.application.casefields.CaseFieldSettings;
import com.securedoc.platform.application.port.out.CaseFieldLock;
import com.securedoc.platform.infrastructure.casefields.JoiningDataSource;
import com.securedoc.platform.infrastructure.casefields.NoKeyService;
import com.securedoc.platform.infrastructure.erasure.CaseFieldErasure;
import com.securedoc.platform.infrastructure.storage.FileEncryption;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;
import java.time.Clock;

/**
 * Task 0061: case fields, platform-core's {@code platform-fields} (docs/install/CASE_FIELDS.md). The keys are the ones of
 * task 0060; without them (a LOCAL development start only) a sensitive field or value is refused, never stored plain.
 */
@Configuration
public class CaseFieldConfig {

    /** What every case field object is built on; made only when the tables are this release's. */
    record Parts(FieldDefinitions definitions, PostgresFieldStore fields, PostgresValueStore values, CustomerId customer,
                 KeyService keys, Fingerprinter fingerprinter) {
    }

    @Bean
    Parts caseFieldParts(DataSource dataSource, Environment environment, FileEncryption protection,
                         ObjectProvider<CustomerId> customers, ObjectProvider<KeyService> keyServices,
                         ObjectProvider<Fingerprinter> fingerprinters) {
        FieldSchema.verify(dataSource);
        DataSource joining = new JoiningDataSource(dataSource);
        PostgresFieldStore fields = new PostgresFieldStore(joining);
        PostgresValueStore values = new PostgresValueStore(joining);
        // FileProtectionConfig has decided already: without keys the app starts only in a LOCAL development start.
        boolean keys = protection.encrypts();
        CustomerId customer = keys ? customers.getObject()
                : developmentCustomer(environment.getProperty("platform.licensing.tenant-id"));
        return new Parts(new FieldDefinitions(fields, values), fields, values, customer,
                keys ? keyServices.getObject() : new NoKeyService(), keys ? fingerprinters.getObject() : null);
    }

    /** A start without the customer's id is refused by the licence check, which tells it better; nothing is stored then. */
    private static CustomerId developmentCustomer(String tenantId) {
        return tenantId == null || tenantId.isBlank() ? CustomerId.of(new java.util.UUID(0, 0)) : CustomerId.fromTenantId(tenantId);
    }

    @Bean
    CaseFieldService caseFieldService(Parts parts, CaseFieldLock lock) {
        return new CaseFieldService(parts.definitions(), new FieldValues(parts.customer(), parts.fields(), parts.values(),
                parts.keys(), Clock.systemUTC()), lock, parts.fingerprinter(), parts.customer());
    }

    @Bean
    CaseFieldSettings caseFieldSettings(Parts parts, CaseFieldLock lock) {
        return new CaseFieldSettings(parts.definitions(), parts.values(), lock, parts.fingerprinter() != null);
    }

    @Bean
    CaseFieldErasure caseFieldErasure(JdbcTemplate jdbc) {
        return new CaseFieldErasure(jdbc);
    }

    /** Erases on connections of its own, so an erasure is never undone by a transaction of the app. No key is needed. */
    @Bean
    EraseHandler caseFieldsEraseHandler(DataSource dataSource, Parts parts, CaseFieldErasure erasure) {
        PostgresValueStore values = new PostgresValueStore(dataSource);
        return erasure.handler(new CaseFieldsEraseHandler(new FieldValues(parts.customer(),
                new PostgresFieldStore(dataSource), values, parts.keys(), Clock.systemUTC()), values));
    }
}
