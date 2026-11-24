package com.securedoc.platform.infrastructure.persistence.adapter;

import com.securedoc.platform.domain.business.PageBatchSettings;
import com.securedoc.platform.domain.business.RegulationPack;
import com.securedoc.platform.domain.business.ReviewMode;
import com.securedoc.platform.domain.business.TenantRedactionPolicy;
import com.securedoc.platform.domain.finding.EntityType;
import com.securedoc.platform.domain.identity.TenantId;
import com.securedoc.platform.infrastructure.persistence.repository.SpringDataTenantRedactionPolicyRepository;
import liquibase.Contexts;
import liquibase.Liquibase;
import liquibase.database.DatabaseFactory;
import liquibase.database.jvm.JdbcConnection;
import liquibase.resource.ClassLoaderResourceAccessor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.orm.jpa.JpaTransactionManager;
import org.springframework.orm.jpa.LocalContainerEntityManagerFactoryBean;
import org.springframework.orm.jpa.vendor.HibernateJpaVendorAdapter;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.persistence.EntityManagerFactory;
import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.EnumSet;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Task 0047, item 1: the policy adapter reads and writes "searchable redacted pages" in the column of migration 048,
 * through the real JPA mapping (Hibernate validates the entities against the migrated schema), and writes no
 * IMAGE_ONLY_REDACTED_PAGES row any more. Its own database on the throwaway server named by
 * {@code LEXAVAULT_TEST_PG_URL}, dropped afterwards.
 */
@EnabledIfEnvironmentVariable(named = "LEXAVAULT_TEST_PG_URL", matches = "jdbc:postgresql:.*")
class SearchableRedactedPagesPolicyPostgresTest {
    private static String adminUrl;
    private static String user;
    private static String password;
    private static String dbName;
    private static AnnotationConfigApplicationContext context;
    private static JdbcTemplate jdbc;
    private static TransactionTemplate tx;
    private static JpaTenantRedactionPolicyRepositoryAdapter policies;

    @BeforeAll
    static void start() throws Exception {
        adminUrl = System.getenv("LEXAVAULT_TEST_PG_URL");
        user = System.getenv("LEXAVAULT_TEST_PG_USER");
        password = System.getenv("LEXAVAULT_TEST_PG_PASSWORD");
        dbName = "lv_t_searchpol_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement s = admin.createStatement()) {
            s.execute("CREATE DATABASE " + dbName);
        }
        String url = adminUrl.replaceFirst("/[^/?]+(\\?|$)", "/" + dbName + "$1");
        try (Connection connection = DriverManager.getConnection(url, user, password)) {
            new Liquibase("db/changelog/db.changelog-master.xml", new ClassLoaderResourceAccessor(),
                    DatabaseFactory.getInstance().findCorrectDatabaseImplementation(new JdbcConnection(connection)))
                    .update(new Contexts());
        }
        JpaSetup.url = url;
        context = new AnnotationConfigApplicationContext(JpaSetup.class);
        DataSource dataSource = context.getBean(DataSource.class);
        jdbc = new JdbcTemplate(dataSource);
        tx = new TransactionTemplate(context.getBean(PlatformTransactionManager.class));
        policies = new JpaTenantRedactionPolicyRepositoryAdapter(
                context.getBean(SpringDataTenantRedactionPolicyRepository.class),
                new JdbcRedactionCatalogStore(new NamedParameterJdbcTemplate(dataSource)));
    }

    @AfterAll
    static void stop() throws Exception {
        if (context != null) context.close();
        if (dbName != null) {
            try (Connection admin = DriverManager.getConnection(adminUrl, user, password); Statement s = admin.createStatement()) {
                s.execute("DROP DATABASE IF EXISTS " + dbName + " WITH (FORCE)");
            }
        }
    }

    @Test
    void theSwitchIsSavedAndReadInThePolicyColumnAndNeverAsARow() {
        TenantId tenant = tenant();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        save(policy(tenant, false, at));
        assertFalse(policies.findByTenantId(tenant).orElseThrow().searchableRedactedPages());
        assertEquals(Boolean.FALSE, jdbc.queryForObject(
                "SELECT searchable_redacted_pages FROM tenant_redaction_policies WHERE tenant_id = ?", Boolean.class,
                tenant.value()));
        assertEquals(14, jdbc.queryForObject("SELECT count(*) FROM tenant_redaction_catalog WHERE tenant_id = ?",
                Integer.class, tenant.value()), "one row per catalog kind, nothing else");

        save(policy(tenant, true, at.plusSeconds(1)));
        TenantRedactionPolicy read = policies.findByTenantId(tenant).orElseThrow();
        assertTrue(read.searchableRedactedPages());
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.EIN), EnumSet.copyOf(read.enabledEntityTypes()),
                "the kinds are unchanged by the move");
    }

    @Test
    void aPolicySavedWithoutTheSettingIsSearchable() {
        TenantId tenant = tenant();
        save(new TenantRedactionPolicy(tenant, EnumSet.of(EntityType.SSN), 0.8, ReviewMode.UNCERTAIN_ONLY, true,
                Instant.now()));
        assertTrue(policies.findByTenantId(tenant).orElseThrow().searchableRedactedPages());
    }

    /** Task 0062: switches, styles, packs and the locked-items mark are stored and read back; a save replaces them. */
    @Test
    void theCatalogIsSavedAndReadBack() {
        TenantId tenant = tenant();
        Instant at = Instant.now().truncatedTo(ChronoUnit.MILLIS);
        save(new TenantRedactionPolicy(tenant, EnumSet.of(EntityType.SSN, EntityType.EIN), 0.8,
                ReviewMode.UNCERTAIN_ONLY, true, at, true, PageBatchSettings.DEFAULTS,
                EnumSet.of(RegulationPack.GLBA, RegulationPack.FRCP_5_2), EnumSet.of(EntityType.SSN), true));
        TenantRedactionPolicy read = policies.findByTenantId(tenant).orElseThrow();
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.EIN, EntityType.CREDIT_CARD_NUMBER, EntityType.CVV,
                EntityType.PIN), EnumSet.copyOf(read.enabledEntityTypes()));
        assertEquals(EnumSet.of(RegulationPack.GLBA, RegulationPack.FRCP_5_2), EnumSet.copyOf(read.packs()));
        assertEquals(EnumSet.of(EntityType.SSN), EnumSet.copyOf(read.keepLastFour()));
        assertTrue(read.lockedItemsOn());

        save(policy(tenant, true, at.plusSeconds(1)));
        read = policies.findByTenantId(tenant).orElseThrow();
        assertTrue(read.packs().isEmpty() && read.keepLastFour().isEmpty() && !read.lockedItemsOn());

        org.junit.jupiter.api.Assertions.assertThrows(org.springframework.dao.DataIntegrityViolationException.class,
                () -> jdbc.update("UPDATE tenant_redaction_catalog SET style = 'KEEP_LAST_FOUR' WHERE tenant_id = ?"
                        + " AND kind_key = 'CVV'", tenant.value()), "the database refuses a style for a locked item");
        jdbc.update("INSERT INTO tenant_redaction_catalog (tenant_id, kind_key, enabled, style, updated_at)"
                + " VALUES (?, 'NOT_A_KIND', true, 'REMOVE_FULLY', now())", tenant.value());
        jdbc.update("INSERT INTO tenant_regulation_packs (tenant_id, pack_key, updated_at) VALUES (?, 'NOT_A_PACK', now())",
                tenant.value());
        read = policies.findByTenantId(tenant).orElseThrow();
        assertEquals(EnumSet.of(EntityType.SSN, EntityType.EIN), EnumSet.copyOf(read.enabledEntityTypes()),
                "an unknown key never switches anything on");
        assertTrue(read.packs().isEmpty());
    }

    private static TenantRedactionPolicy policy(TenantId tenant, boolean searchable, Instant at) {
        return new TenantRedactionPolicy(tenant, EnumSet.of(EntityType.SSN, EntityType.EIN), 0.8,
                ReviewMode.UNCERTAIN_ONLY, true, at, searchable);
    }

    private static void save(TenantRedactionPolicy policy) {
        tx.executeWithoutResult(s -> policies.save(policy));
    }

    private static TenantId tenant() {
        TenantId id = TenantId.generate();
        Timestamp now = Timestamp.from(Instant.now());
        String slug = "searchable-" + UUID.randomUUID().toString().substring(0, 8);
        jdbc.update("INSERT INTO tenants (id, name, slug, status, created_at, updated_at) VALUES (?, ?, ?, 'ACTIVE', ?, ?)",
                id.value(), slug, slug, now, now);
        return id;
    }

    /** The JPA part of the application: the entities, the Spring Data repositories, one transaction manager. */
    @Configuration
    @EnableJpaRepositories(basePackageClasses = SpringDataTenantRedactionPolicyRepository.class)
    static class JpaSetup {
        static String url;

        @Bean
        DataSource dataSource() {
            return new DriverManagerDataSource(url, user, password);
        }

        @Bean
        LocalContainerEntityManagerFactoryBean entityManagerFactory(DataSource dataSource) {
            LocalContainerEntityManagerFactoryBean factory = new LocalContainerEntityManagerFactoryBean();
            factory.setDataSource(dataSource);
            factory.setPackagesToScan("com.securedoc.platform.infrastructure.persistence.entity");
            factory.setJpaVendorAdapter(new HibernateJpaVendorAdapter());
            factory.setJpaPropertyMap(Map.of(
                    "hibernate.hbm2ddl.auto", "validate",
                    "hibernate.physical_naming_strategy",
                    "org.hibernate.boot.model.naming.CamelCaseToUnderscoresNamingStrategy",
                    "hibernate.implicit_naming_strategy",
                    "org.springframework.boot.hibernate.SpringImplicitNamingStrategy"));
            return factory;
        }

        @Bean
        PlatformTransactionManager transactionManager(EntityManagerFactory entityManagerFactory) {
            return new JpaTransactionManager(entityManagerFactory);
        }
    }
}
