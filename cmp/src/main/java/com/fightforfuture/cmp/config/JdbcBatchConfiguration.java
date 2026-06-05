package com.fightforfuture.cmp.config;

import org.springframework.batch.core.configuration.support.JdbcDefaultBatchConfiguration;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Configuration;
import org.springframework.transaction.PlatformTransactionManager;

import javax.sql.DataSource;

/**
 * Extends JdbcDefaultBatchConfiguration so Spring Batch persists all job
 * metadata (BATCH_JOB_INSTANCE, BATCH_JOB_EXECUTION, etc.) to PostgreSQL
 * instead of using the default in-memory ResourcelessJobRepository.
 *
 * We exclude Spring Boot's BatchAutoConfiguration (which uses DefaultBatchConfiguration
 * = in-memory) and replace it with this class.
 */
@Configuration
public class JdbcBatchConfiguration extends JdbcDefaultBatchConfiguration {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Override
    protected DataSource getDataSource() {
        return dataSource;
    }

    @Override
    protected PlatformTransactionManager getTransactionManager() {
        return transactionManager;
    }

    @Override
    protected String getTablePrefix() {
        return "BATCH_";
    }
}
