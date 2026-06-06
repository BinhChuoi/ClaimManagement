package com.fightforfuture.cmp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;
import java.time.LocalDate;

@Entity
@Table(name = "initial_job")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class InitialJob extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.SEQUENCE, generator = "initial_job_seq")
    @SequenceGenerator(name = "initial_job_seq", sequenceName = "initial_job_id_seq", allocationSize = 5)
    private Long id;

    @Column(name = "start_date", nullable = false)
    private LocalDate startDate;

    @Column(name = "end_date", nullable = false)
    private LocalDate endDate;

    @Column(name = "job_type", nullable = false, length = 50)
    private String jobType;

    @Column(name = "job_key", nullable = false, length = 20)
    private String jobKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private InitialJobStatus status;

    @Column(name = "worker_id", length = 100)
    private String workerId;

    @Column(name = "started_at")
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "records_fetched")
    private Integer recordsFetched;

    @Column(name = "error_message")
    private String errorMessage;

    @Column(name = "retry_count", nullable = false)
    private int retryCount;
}
