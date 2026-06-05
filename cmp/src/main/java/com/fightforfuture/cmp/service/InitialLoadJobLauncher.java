package com.fightforfuture.cmp.service;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.Job;
import org.springframework.batch.core.job.parameters.JobParameters;
import org.springframework.batch.core.job.parameters.JobParametersBuilder;
import org.springframework.batch.core.launch.support.TaskExecutorJobLauncher;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.stereotype.Component;

import java.net.InetAddress;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.ScheduledFuture;
import java.util.stream.Collectors;

@Slf4j
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(name = "app.enable-initial-load", havingValue = "true")
public class InitialLoadJobLauncher implements ApplicationRunner {

    private static final int  BATCH_SIZE    = 10;
    private static final long INITIAL_DELAY = 15;  // seconds
    private static final long POLL_INTERVAL = 60;  // seconds

    private final Job                     initialLoadJob;
    private final TaskExecutorJobLauncher asyncJobLauncher;
    private final InitialJobService       initialJobService;
    private final ThreadPoolTaskScheduler taskScheduler;

    private volatile ScheduledFuture<?> scheduledTask;

    @Override
    public void run(ApplicationArguments args) {
        log.info("[InitialLoad] Enabled — polling every {}s (initial delay {}s)", POLL_INTERVAL, INITIAL_DELAY);
        scheduledTask = taskScheduler.scheduleWithFixedDelay(
                this::poll,
                Instant.now().plusSeconds(INITIAL_DELAY),
                Duration.ofSeconds(POLL_INTERVAL)
        );
    }

    private void poll() {
        // ── Step 1: Atomically claim next batch of PENDING jobs ───────────────
        // SELECT FOR UPDATE SKIP LOCKED inside a transaction:
        //   - multiple instances poll simultaneously → each gets DIFFERENT job IDs
        //   - status is set to RUNNING in the DB before we launch anything
        //   - no race window between ID selection and status update
        String workerId = resolveWorkerId();
        List<Long> ids = initialJobService.claimNextJobIds(workerId, BATCH_SIZE);

        if (ids.isEmpty()) {
            // Either all jobs are done, or another instance grabbed them all via SKIP LOCKED.
            // Check if anything is still pending (not just running) before terminating.
            if (!initialJobService.hasPendingJobs()) {
                log.info("[InitialLoad] No pending jobs remaining — terminating poller");
                scheduledTask.cancel(false);
            } else {
                log.debug("[InitialLoad] No jobs claimed this tick (taken by another instance)");
            }
            return;
        }

        // ── Step 2: Launch Spring Batch job for the claimed IDs ───────────────
        // Jobs are already RUNNING in DB — Spring Batch just processes them.
        // If launch fails, markFailed() is called by StepListener.
        String jobIds = ids.stream().map(String::valueOf).collect(Collectors.joining(","));
        log.info("[InitialLoad] Launching batch for claimed job IDs: [{}]", jobIds);

        try {
            JobParameters params = new JobParametersBuilder()
                    .addString("jobIds", jobIds)
                    .addLocalDateTime("launchedAt", LocalDateTime.now())
                    .toJobParameters();
            asyncJobLauncher.run(initialLoadJob, params);
        } catch (Exception e) {
            log.error("[InitialLoad] Failed to launch batch — marking jobs failed: {}", e.getMessage());
            ids.forEach(id -> initialJobService.markFailed(id, "Launch failed: " + e.getMessage()));
        }
    }

    private String resolveWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "/" + Thread.currentThread().getName();
        } catch (Exception e) {
            return Thread.currentThread().getName();
        }
    }
}
