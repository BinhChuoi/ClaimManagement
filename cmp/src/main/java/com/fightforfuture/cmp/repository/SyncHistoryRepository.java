package com.fightforfuture.cmp.repository;

import com.fightforfuture.cmp.entity.SyncHistory;
import com.fightforfuture.cmp.entity.SyncStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface SyncHistoryRepository extends JpaRepository<SyncHistory, Long> {

    /**
     * Find the most recent completed sync — used to get the delta cursor.
     * Returns empty if no sync has ever completed (triggers initial load).
     */
    Optional<SyncHistory> findTopByStatusOrderByStartedAtDesc(SyncStatus status);

    /**
     * Check if any sync is currently running — prevents overlapping runs.
     */
    boolean existsByStatus(SyncStatus status);
}
