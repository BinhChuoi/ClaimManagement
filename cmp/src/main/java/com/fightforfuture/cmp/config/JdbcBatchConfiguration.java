package com.fightforfuture.cmp.config;

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Isolation;

import javax.sql.DataSource;

/**
 * Configures Spring Batch to use PostgreSQL (JDBC) for all job metadata persistence
 * instead of the default in-memory ResourcelessJobRepository.
 *
 * ── Two transaction managers ─────────────────────────────────────────────────
 *
 * We deliberately use a separate DataSourceTransactionManager for Spring Batch
 * metadata instead of the application's JpaTransactionManager. Reasons:
 *
 *   1. Spring Batch metadata (BATCH_* tables) uses plain JDBC — no JPA entities.
 *      Running batch operations through JpaTransactionManager adds unnecessary
 *      Hibernate session management, first-level cache, and entity lifecycle
 *      overhead for data that has no JPA representation.
 *
 *   2. DataSourceTransactionManager is lighter — it opens a raw JDBC connection
 *      without Hibernate involvement. This is the correct tool for JDBC-only
 *      operations.
 *
 *   3. Isolation levels can be tuned independently:
 *      - JpaTransactionManager (domain entities): READ_COMMITTED (Spring Boot default)
 *      - DataSourceTransactionManager (batch metadata): READ_COMMITTED (see below)
 *
 *   The @Qualifier("batchTransactionManager") annotation ensures Spring Batch
 *   uses this manager, while the JPA manager remains the primary for all
 *   @Transactional methods in services.
 *
 * ── Why READ_COMMITTED is safe for batch metadata ────────────────────────────
 *
 * Spring Batch's default isolation for JobRepository operations is SERIALIZABLE.
 * In a multi-instance deployment this causes:
 *
 *   ERROR: could not serialize access due to read/write dependencies among transactions
 *
 * Root cause:
 *   Two instances launch jobs simultaneously. Both SELECT FROM batch_job_instance
 *   return 0 rows (empty table). PostgreSQL places a predicate lock on the same
 *   index page for both. Both then INSERT their own distinct rows into that page.
 *   PostgreSQL detects a circular read/write dependency → aborts one as "pivot".
 *
 * Why READ_COMMITTED is safe:
 *
 *   1. SELECT FOR UPDATE SKIP LOCKED in claimNextJobIds() guarantees each instance
 *      gets a distinct set of job IDs before launching.
 *
 *   2. job_key = MD5(jobIds + launchedAt). Different jobIds → different hash →
 *      different job_key per instance. Two instances cannot produce the same key.
 *
 *   3. UNIQUE(job_name, job_key) on batch_job_instance is the real guard.
 *      If identical keys were somehow submitted, the DB rejects the duplicate
 *      with a constraint violation (no silent data corruption).
 *
 *   4. Each instance only reads/writes its own batch_job_execution and
 *      batch_step_execution rows. No cross-instance read-then-write exists.
 *
 *   Conclusion: SERIALIZABLE was protecting against a phantom that cannot occur
 *   in this design. READ_COMMITTED + UNIQUE constraint + SKIP LOCKED = correct.
 */
@Configuration
public class JdbcBatchConfiguration extends JdbcDefaultBatchConfiguration {

    @Autowired
    private DataSource dataSource;

    /**
     * Dedicated transaction manager for Spring Batch metadata (BATCH_* tables).
     * Uses plain JDBC — no Hibernate / JPA session involved.
     * Named "batchTransactionManager" so it doesn't conflict with the
     * JpaTransactionManager that Spring Boot auto-configures as primary.
     */
    @Bean("batchTransactionManager")
    public PlatformTransactionManager batchTransactionManager() {
        return new DataSourceTransactionManager(dataSource);
    }

    @Override
    protected DataSource getDataSource() {
        return dataSource;
    }

    /**
     * Use the dedicated JDBC transaction manager, not the JPA one.
     * Spring Batch metadata has no JPA entities — no need for Hibernate.
     */
    @Override
    protected PlatformTransactionManager getTransactionManager() {
        return batchTransactionManager();
    }

    @Override
    protected String getTablePrefix() {
        return "BATCH_";
    }

    @Override
    protected Isolation getIsolationLevelForCreate() {
        return Isolation.READ_COMMITTED;
    }
}
