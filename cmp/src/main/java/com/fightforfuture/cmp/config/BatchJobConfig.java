package com.fightforfuture.cmp.config;

import com.fightforfuture.cmp.batch.InitialJobPartitioner;
import com.fightforfuture.cmp.batch.InitialLoadStepListener;
import com.fightforfuture.cmp.batch.InvoiceCompositeItemWriter;
import com.fightforfuture.cmp.batch.InvoiceItemProcessor;
import com.fightforfuture.cmp.batch.S3ParquetItemReader;
import com.fightforfuture.cmp.dto.AthenaInvoiceRow;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.builder.JobBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.batch.core.repository.JobRepository;
import org.springframework.batch.core.step.Step;
import org.springframework.batch.core.step.builder.StepBuilder;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.transaction.PlatformTransactionManager;

@Configuration
public class BatchJobConfig {

    private static final int CHUNK_SIZE = 500;
    private static final int GRID_SIZE  = 10;

    @Bean
    public Job initialLoadJob(JobRepository jobRepository, Step partitionedLoadStep) {
        return new JobBuilder("initialLoadJob", jobRepository)
                .start(partitionedLoadStep)
                .build();
    }

    @Bean
    public Step partitionedLoadStep(JobRepository jobRepository,
                                    InitialJobPartitioner partitioner,
                                    Step workerStep) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(GRID_SIZE);
        executor.setMaxPoolSize(GRID_SIZE);
        executor.setThreadNamePrefix("batch-worker-");
        executor.initialize();

        return new StepBuilder("partitionedLoadStep", jobRepository)
                .partitioner("workerStep", partitioner)
                .step(workerStep)
                .gridSize(GRID_SIZE)
                .taskExecutor(executor)
                .build();
    }

    @Bean
    public Step workerStep(JobRepository jobRepository,
                           S3ParquetItemReader reader,
                           InvoiceItemProcessor processor,
                           InvoiceCompositeItemWriter writer,
                           InitialLoadStepListener listener,
                           @Qualifier("batchTransactionManager") PlatformTransactionManager transactionManager) {
        return new StepBuilder("workerStep", jobRepository)
                .<AthenaInvoiceRow, AthenaInvoiceRow>chunk(CHUNK_SIZE)
                .transactionManager(transactionManager)
                .reader(reader)
                .processor(processor)
                .writer(writer)
                .listener(listener)
                .build();
    }

    @Bean
    public TaskExecutorJobLauncher asyncJobLauncher(JobRepository jobRepository) throws Exception {
        TaskExecutorJobLauncher launcher = new TaskExecutorJobLauncher();
        launcher.setJobRepository(jobRepository);
        launcher.setTaskExecutor(new SimpleAsyncTaskExecutor("batch-launcher-"));
        launcher.afterPropertiesSet();
        return launcher;
    }
}
