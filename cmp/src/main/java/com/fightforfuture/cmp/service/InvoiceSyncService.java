package com.fightforfuture.cmp.service;

import com.fightforfuture.cmp.dto.AthenaInvoiceRow;
import com.fightforfuture.cmp.repository.InvoiceHeaderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoiceSyncService {

    private final InvoiceHeaderRepository   invoiceHeaderRepository;
    private final AthenaService             athenaService;
    private final InvoicePersistenceService persistenceService;

    private final AtomicBoolean running = new AtomicBoolean(false);

    /**
     * Delta sync — runs every hour.
     * Uses MAX(invoice_number) from the DB as the starting point.
     * Skipped until the initial load (Spring Batch) has populated at least one row.
     */
    @Scheduled(initialDelay = 10_000, fixedDelay = 3_600_000)
    public void sync() {
        if (!running.compareAndSet(false, true)) {
            log.warn("[Sync] Previous run still in progress — skipping");
            return;
        }

        try {
            long fromInvoiceNumber = invoiceHeaderRepository.findMaxInvoiceNumber();

            if (fromInvoiceNumber == 0) {
                log.info("[Sync] No data yet — waiting for initial load to complete");
                return;
            }

            log.info("[Sync] Delta sync from invoice_number > {}", fromInvoiceNumber);
            List<AthenaInvoiceRow> rows = athenaService.fetchDelta(fromInvoiceNumber);

            if (rows.isEmpty()) {
                log.info("[Sync] No new invoices");
                return;
            }

            int saved = persistenceService.save(rows);
            long maxInvoiceNum = rows.stream()
                    .mapToLong(AthenaInvoiceRow::getInvoiceNumber)
                    .max().orElse(fromInvoiceNumber);

            log.info("[Sync] Completed — {} records synced, up to invoice_number {}", saved, maxInvoiceNum);

        } catch (Exception e) {
            log.error("[Sync] Failed: {}", e.getMessage(), e);
        } finally {
            running.set(false);
        }
    }
}
