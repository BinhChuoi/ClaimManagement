package com.fightforfuture.cmp.controller;

import com.fightforfuture.cmp.entity.InitialJob;
import com.fightforfuture.cmp.repository.InitialJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.job.JobExecution;
import org.springframework.batch.core.launch.JobOperator;
import org.springframework.batch.core.repository.explore.JobExplorer;
// Spring Batch 6: JobExplorer is in org.springframework.batch.core.repository.explore
import org.springframework.batch.core.step.StepExecution;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@RestController
@RequestMapping("/admin/batch")
@RequiredArgsConstructor
public class BatchAdminController {

    private final JobExplorer         jobExplorer;
    private final JobOperator         jobOperator;
    private final InitialJobRepository initialJobRepository;

    /** GET /admin/batch/jobs — list all job executions */
    @GetMapping("/jobs")
    public List<Map<String, Object>> listJobs() {
        return jobExplorer.getJobInstances("initialLoadJob", 0, 100)
                .stream()
                .flatMap(instance -> jobExplorer.getJobExecutions(instance).stream())
                .map(this::toMap)
                .collect(Collectors.toList());
    }

    /** GET /admin/batch/jobs/{executionId} — get one execution with steps */
    @GetMapping("/jobs/{executionId}")
    public Map<String, Object> getJob(@PathVariable Long executionId) {
        JobExecution execution = jobExplorer.getJobExecution(executionId);
        if (execution == null) throw new RuntimeException("Execution not found: " + executionId);
        Map<String, Object> result = toMap(execution);
        result.put("steps", execution.getStepExecutions().stream()
                .map(this::stepToMap)
                .collect(Collectors.toList()));
        return result;
    }

    /** GET /admin/batch/running — show currently running executions */
    @GetMapping("/running")
    public List<Long> running() throws Exception {
        return List.copyOf(jobOperator.getRunningExecutions("initialLoadJob"));
    }

    /** POST /admin/batch/restart/{executionId} — restart a FAILED execution */
    @PostMapping("/restart/{executionId}")
    public Map<String, Object> restart(@PathVariable Long executionId) throws Exception {
        log.info("[BatchAdmin] Restarting execution {}", executionId);
        Long newExecutionId = jobOperator.restart(executionId);
        return Map.of(
                "message", "Restarted successfully",
                "originalExecutionId", executionId,
                "newExecutionId", newExecutionId
        );
    }

    /** POST /admin/batch/stop/{executionId} — stop a RUNNING execution */
    @PostMapping("/stop/{executionId}")
    public Map<String, Object> stop(@PathVariable Long executionId) throws Exception {
        log.info("[BatchAdmin] Stopping execution {}", executionId);
        boolean stopped = jobOperator.stop(executionId);
        return Map.of("stopped", stopped, "executionId", executionId);
    }

    /** GET /admin/batch/initial-jobs — view initial_job table */
    @GetMapping("/initial-jobs")
    public List<Map<String, Object>> initialJobs() {
        return initialJobRepository.findAll().stream()
                .map(job -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("id", job.getId());
                    m.put("jobKey", job.getJobKey());
                    m.put("status", job.getStatus());
                    m.put("startDate", job.getStartDate());
                    m.put("endDate", job.getEndDate());
                    m.put("workerId", job.getWorkerId());
                    m.put("startedAt", job.getStartedAt());
                    m.put("completedAt", job.getCompletedAt());
                    m.put("recordsFetched", job.getRecordsFetched());
                    m.put("retryCount", job.getRetryCount());
                    m.put("errorMessage", job.getErrorMessage());
                    return m;
                })
                .collect(Collectors.toList());
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Map<String, Object> toMap(JobExecution e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("executionId", e.getId());
        m.put("instanceId", e.getJobInstance().getInstanceId());
        m.put("jobName", e.getJobInstance().getJobName());
        m.put("status", e.getStatus().name());
        m.put("exitCode", e.getExitStatus().getExitCode());
        m.put("jobIds", e.getJobParameters().getString("jobIds"));
        m.put("createTime", e.getCreateTime());
        m.put("startTime", e.getStartTime());
        m.put("endTime", e.getEndTime());
        m.put("stepCount", e.getStepExecutions().size());
        return m;
    }

    private Map<String, Object> stepToMap(StepExecution s) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("stepName", s.getStepName());
        m.put("status", s.getStatus().name());
        m.put("readCount", s.getReadCount());
        m.put("writeCount", s.getWriteCount());
        m.put("commitCount", s.getCommitCount());
        m.put("rollbackCount", s.getRollbackCount());
        m.put("exitCode", s.getExitStatus().getExitCode());
        m.put("startTime", s.getStartTime());
        m.put("endTime", s.getEndTime());
        return m;
    }
}
