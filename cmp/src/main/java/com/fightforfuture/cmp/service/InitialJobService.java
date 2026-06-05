package com.fightforfuture.cmp.service;

import com.fightforfuture.cmp.entity.InitialJob;
import com.fightforfuture.cmp.entity.InitialJobStatus;
import com.fightforfuture.cmp.repository.InitialJobRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.stream.Collectors;

// Note: markRunning() removed — status is set to RUNNING atomically inside claimNextJobIds()

@Slf4j
@Service
@RequiredArgsConstructor
public class InitialJobService {

    private final InitialJobRepository initialJobRepository;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Atomically claims the next {@code limit} PENDING jobs and marks them RUNNING
     * in a single transaction using SELECT FOR UPDATE SKIP LOCKED.
     *
     * Safe for high concurrency — multiple instances calling this simultaneously
     * each receive a different set of job IDs. An instance that finds nothing
     * (all taken by others) gets an empty list.
     *
     * Status is RUNNING in the DB before the caller launches any Spring Batch job,
     * so there is no window where two instances can claim the same jobs.
     */
    @Transactional
    public List<Long> claimNextJobIds(String workerId, int limit) {
        @SuppressWarnings("unchecked")
        List<InitialJob> claimed = entityManager.createNativeQuery(
                "SELECT * FROM initial_job " +
                "WHERE status = 'PENDING' " +
                "ORDER BY start_date " +
                "LIMIT :limit " +
                "FOR UPDATE SKIP LOCKED",
                InitialJob.class
        ).setParameter("limit", limit).getResultList();

        if (claimed.isEmpty()) return List.of();

        Instant now = Instant.now();
        for (InitialJob job : claimed) {
            job.setStatus(InitialJobStatus.RUNNING);
            job.setWorkerId(workerId);
            job.setStartedAt(now);
        }
        // Flush inside @Transactional — UPDATE committed when method returns
        // Other instances using SKIP LOCKED will skip these rows immediately

        List<Long> ids = claimed.stream().map(InitialJob::getId).collect(Collectors.toList());
        log.info("[InitialJob] Claimed {} jobs as RUNNING: {} worker={}", ids.size(), ids, workerId);
        return ids;
    }

    @Transactional
    public void markCompleted(Long jobId, int recordsFetched) {
        InitialJob job = findById(jobId);
        job.setStatus(InitialJobStatus.COMPLETED);
        job.setRecordsFetched(recordsFetched);
        job.setCompletedAt(Instant.now());
        log.info("[InitialJob] Completed job {} — {} records", jobId, recordsFetched);
    }

    @Transactional
    public void markFailed(Long jobId, String errorMessage) {
        InitialJob job = findById(jobId);
        job.setStatus(InitialJobStatus.FAILED);
        job.setErrorMessage(errorMessage);
        job.setCompletedAt(Instant.now());
        job.setRetryCount(job.getRetryCount() + 1);
        log.error("[InitialJob] Failed job {} — {}", jobId, errorMessage);
    }

    public boolean hasPendingJobs() {
        return initialJobRepository.existsByStatus(InitialJobStatus.PENDING);
    }

    private InitialJob findById(Long jobId) {
        return initialJobRepository.findById(jobId)
                .orElseThrow(() -> new IllegalStateException("InitialJob not found: " + jobId));
    }
}
