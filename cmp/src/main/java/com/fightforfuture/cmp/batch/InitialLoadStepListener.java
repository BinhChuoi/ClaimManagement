package com.fightforfuture.cmp.batch;

import com.fightforfuture.cmp.service.InitialJobService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.BatchStatus;
import org.springframework.batch.core.ExitStatus;
import org.springframework.batch.core.listener.StepExecutionListener;
import org.springframework.batch.core.step.StepExecution;
import org.springframework.stereotype.Component;

import java.net.InetAddress;

@Slf4j
@Component
@RequiredArgsConstructor
public class InitialLoadStepListener implements StepExecutionListener {

    private final InitialJobService initialJobService;

    @Override
    public void beforeStep(StepExecution stepExecution) {
        // Status already set to RUNNING by InitialLoadJobLauncher.claimNextJobIds()
        // before Spring Batch started — nothing to do here.
    }

    @Override
    public ExitStatus afterStep(StepExecution stepExecution) {
        long jobId = stepExecution.getExecutionContext().getLong("jobId", -1L);
        if (jobId == -1L) return stepExecution.getExitStatus(); // master step

        if (stepExecution.getStatus() == BatchStatus.COMPLETED) {
            initialJobService.markCompleted(jobId, (int) stepExecution.getWriteCount());
        } else {
            String error = stepExecution.getFailureExceptions().stream()
                    .map(Throwable::getMessage)
                    .findFirst()
                    .orElse("Unknown error");
            initialJobService.markFailed(jobId, error);
        }
        return stepExecution.getExitStatus();
    }

    private String resolveWorkerId() {
        try {
            return InetAddress.getLocalHost().getHostName() + "/" + Thread.currentThread().getName();
        } catch (Exception e) {
            return Thread.currentThread().getName();
        }
    }
}
