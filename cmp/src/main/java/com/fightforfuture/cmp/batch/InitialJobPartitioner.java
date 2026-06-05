package com.fightforfuture.cmp.batch;

import com.fightforfuture.cmp.entity.InitialJob;
import com.fightforfuture.cmp.repository.InitialJobRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.core.configuration.annotation.JobScope;
import org.springframework.batch.core.partition.Partitioner;
import org.springframework.batch.infrastructure.item.ExecutionContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

@Slf4j
@Component
@JobScope
@RequiredArgsConstructor
public class InitialJobPartitioner implements Partitioner {

    private final InitialJobRepository initialJobRepository;

    /**
     * Injected from JobParameters — set by the launcher before the job starts.
     * Same value on both initial run and any restart → deterministic partitioning.
     */
    @Value("#{jobParameters['jobIds']}")
    private String jobIds;

    @Override
    public Map<String, ExecutionContext> partition(int gridSize) {
        List<Long> ids = Arrays.stream(jobIds.split(","))
                .map(Long::parseLong)
                .collect(Collectors.toList());

        List<InitialJob> jobs = initialJobRepository.findAllById(ids);
        log.info("[Partitioner] Creating {} partitions for job IDs: {}", jobs.size(), ids);

        Map<String, ExecutionContext> partitions = new LinkedHashMap<>();
        for (InitialJob job : jobs) {
            ExecutionContext ctx = new ExecutionContext();
            ctx.putLong("jobId", job.getId());
            ctx.putString("startDate", job.getStartDate().toString());
            partitions.put("partition:" + job.getJobKey(), ctx);
        }
        return partitions;
    }
}
