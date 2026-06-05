package com.fightforfuture.cmp.repository;

import com.fightforfuture.cmp.entity.InitialJob;
import com.fightforfuture.cmp.entity.InitialJobStatus;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;

public interface InitialJobRepository extends JpaRepository<InitialJob, Long> {

    boolean existsByStatus(InitialJobStatus status);

    List<InitialJob> findByStatusOrderByStartDate(InitialJobStatus status);

    List<InitialJob> findByStatusOrderByStartDate(InitialJobStatus status, Pageable pageable);
}
