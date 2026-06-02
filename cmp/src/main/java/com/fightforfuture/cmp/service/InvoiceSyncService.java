package com.fightforfuture.cmp.service;

import com.fightforfuture.cmp.dto.AthenaInvoiceRow;
import com.fightforfuture.cmp.entity.SyncHistory;
import com.fightforfuture.cmp.entity.SyncStatus;
import com.fightforfuture.cmp.repository.SyncHistoryRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceSyncService {

    private final SyncHistoryRepository    syncHistoryRepository;
    private final AthenaService            athenaService;
    private final InvoicePersistenceService persistenceService;

    // Date range for initial load — matches data lake partition range
    private static final YearMonth INITIAL_LOAD_FROM = YearMonth.of(2022, 1);
    private static final YearMonth INITIAL_LOAD_TO   = YearMonth.of(2022, 6);  // benchmark: 1 chunk only

    private static final int INITIAL_LOAD_YEAR_FROM = 2022;
    private static final int INITIAL_LOAD_YEAR_TO   = 2024;

    /**
     * Runs on application startup (initialDelay = 0) and every 1 hour after.
     * Each run creates one record in sync_history.
     */
    @Scheduled(initialDelay = 10000, fixedDelay = 3_600_000)
    public void sync() {

        // ── Guard: skip if another run is still in progress ──────────────
        if (syncHistoryRepository.existsByStatus(SyncStatus.RUNNING)) {
            log.warn("[Sync] Previous run still RUNNING — skipping this cycle");
            return;
        }

        // ── Step 1: Decide from_invoice_number ───────────────────────────
        Optional<SyncHistory> lastCompleted = syncHistoryRepository
                .findTopByStatusOrderByStartedAtDesc(SyncStatus.COMPLETED);

        long fromInvoiceNumber = lastCompleted
                .map(h -> h.getToInvoiceNumber() + 1)
                .orElse(0L);   // 0 = no history → initial load from the beginning

        boolean isInitialLoad = lastCompleted.isEmpty();
        log.info("[Sync] Starting {} load from invoice_number > {}",
                isInitialLoad ? "INITIAL" : "DELTA", fromInvoiceNumber);

        // ── Step 2: Create RUNNING record ────────────────────────────────
        SyncHistory history = SyncHistory.builder()
                .fromInvoiceNumber(fromInvoiceNumber)
                .status(SyncStatus.RUNNING)
                .startedAt(Instant.now())
                .build();
        syncHistoryRepository.save(history);

        // ── Step 3: Fetch from Athena + insert into DB ───────────────────
        try {
            int totalSynced     = 0;
            long maxInvoiceNum  = fromInvoiceNumber;

            if (isInitialLoad) {
                // 6-month chunks: 6 fetches total for 2022-2024
                // ~500K rows per chunk — faster than month-by-month (36 calls)
                // and safer than full year (1M rows at once)
                YearMonth cursor = INITIAL_LOAD_FROM;
                while (!cursor.isAfter(INITIAL_LOAD_TO)) {
                    YearMonth chunkEnd = cursor.plusMonths(5).isAfter(INITIAL_LOAD_TO)
                            ? INITIAL_LOAD_TO
                            : cursor.plusMonths(5);

                    log.info("[Sync] Initial load chunk: {}/{} → {}/{}",
                            cursor.getYear(), cursor.getMonthValue(),
                            chunkEnd.getYear(), chunkEnd.getMonthValue());

                    List<AthenaInvoiceRow> rows = athenaService.fetchByMonthRange(
                            cursor.getYear(),
                            cursor.getMonthValue(),
                            chunkEnd.getMonthValue());

                    totalSynced += persistenceService.save(rows);
                    maxInvoiceNum = rows.stream()
                            .mapToLong(AthenaInvoiceRow::getInvoiceNumber)
                            .max().orElse(maxInvoiceNum);

                    cursor = chunkEnd.plusMonths(1);
                }
            } else {
                // Delta load: fetch only new invoices since last sync
                List<AthenaInvoiceRow> rows = athenaService.fetchDelta(fromInvoiceNumber);

                totalSynced = persistenceService.save(rows);
                maxInvoiceNum = rows.stream()
                        .mapToLong(AthenaInvoiceRow::getInvoiceNumber)
                        .max().orElse(fromInvoiceNumber);
            }

            // ── Step 4: Mark COMPLETED ───────────────────────────────────
            history.setToInvoiceNumber(maxInvoiceNum);
            history.setRecordsSynced(totalSynced);
            history.setStatus(SyncStatus.COMPLETED);
            history.setCompletedAt(Instant.now());
            syncHistoryRepository.save(history);

            log.info("[Sync] COMPLETED — synced {} records, up to invoice_number {}",
                    totalSynced, maxInvoiceNum);

        } catch (Exception e) {

            // ── Step 5: Mark FAILED ──────────────────────────────────────
            history.setStatus(SyncStatus.FAILED);
            history.setCompletedAt(Instant.now());
            history.setErrorMessage(e.getMessage());
            syncHistoryRepository.save(history);

            log.error("[Sync] FAILED — {}", e.getMessage(), e);
        }
    }
}
