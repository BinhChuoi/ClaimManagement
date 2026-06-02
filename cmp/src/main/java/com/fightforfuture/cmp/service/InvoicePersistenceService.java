package com.fightforfuture.cmp.service;

import com.fightforfuture.cmp.dto.AthenaInvoiceRow;
import com.fightforfuture.cmp.entity.InvoiceHeader;
import com.fightforfuture.cmp.entity.InvoiceLineItem;
import com.fightforfuture.cmp.entity.ReturnOrder;
import com.fightforfuture.cmp.mapper.InvoiceMapper;
import com.fightforfuture.cmp.repository.InvoiceHeaderRepository;
import com.fightforfuture.cmp.repository.InvoiceLineItemRepository;
import com.fightforfuture.cmp.repository.ReturnOrderRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoicePersistenceService {

    private static final int BATCH_SIZE = 500;

    private final InvoiceHeaderRepository   invoiceHeaderRepository;
    private final InvoiceLineItemRepository invoiceLineItemRepository;
    private final ReturnOrderRepository     returnOrderRepository;
    private final InvoiceMapper             invoiceMapper;

    @PersistenceContext
    private EntityManager entityManager;

    /**
     * Converts Athena rows → entities via InvoiceMapper, then saves to DB.
     * FK order: headers first → line items → return orders.
     *
     * @return number of line item rows saved
     */
    @Transactional
    public int save(List<AthenaInvoiceRow> rows) {
        if (rows.isEmpty()) return 0;

        // ── 1. Convert + deduplicate headers ─────────────────────────────
        Map<Long, InvoiceHeader> headerMap = new LinkedHashMap<>();
        for (AthenaInvoiceRow row : rows) {
            headerMap.computeIfAbsent(
                    row.getInvoiceNumber(),
                    id -> invoiceMapper.toHeader(row)
            );
        }

        // ── 2. Upsert headers (ON CONFLICT DO UPDATE — handles duplicate invoice_numbers)
        upsertHeaders(new ArrayList<>(headerMap.values()));

        // ── 3. Convert + save line items ──────────────────────────────────
        List<InvoiceLineItem> lineItems = rows.stream()
                .map(row -> invoiceMapper.toLineItem(row, headerMap.get(row.getInvoiceNumber())))
                .collect(Collectors.toList());

        saveInBatches("line_items", lineItems, invoiceLineItemRepository);

        // ── 4. Convert + deduplicate + save return orders ─────────────────
        Map<String, ReturnOrder> returnOrderMap = new LinkedHashMap<>();
        for (AthenaInvoiceRow row : rows) {
            if (row.getReturnOrderNo() != null && !row.getReturnOrderNo().isBlank()) {
                returnOrderMap.computeIfAbsent(
                        row.getReturnOrderNo(),
                        id -> invoiceMapper.toReturnOrder(row, headerMap.get(row.getInvoiceNumber()))
                );
            }
        }

        if (!returnOrderMap.isEmpty()) {
            upsertReturnOrders(new ArrayList<>(returnOrderMap.values()));
        }

        log.info("[Persist] Saved {} headers, {} line items, {} return orders",
                headerMap.size(), lineItems.size(), returnOrderMap.size());

        return lineItems.size();
    }

    // ── Upsert return orders ──────────────────────────────────────────────────

    private void upsertReturnOrders(List<ReturnOrder> orders) {
        int total = orders.size();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<ReturnOrder> batch = orders.subList(i, Math.min(i + BATCH_SIZE, total));
            batch.forEach(returnOrderRepository::upsert);
            entityManager.flush();
            entityManager.clear();
        }
    }

    // ── Upsert headers ───────────────────────────────────────────────────────

    private void upsertHeaders(List<InvoiceHeader> headers) {
        int total = headers.size();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<InvoiceHeader> batch = headers.subList(i, Math.min(i + BATCH_SIZE, total));
            batch.forEach(invoiceHeaderRepository::upsert);
            entityManager.flush();
            entityManager.clear();
            log.debug("[Persist] headers upserted {}/{}", Math.min(i + BATCH_SIZE, total), total);
        }
    }

    // ── Batch helper ──────────────────────────────────────────────────────────

    private <T, ID> void saveInBatches(String label,
                                       List<T> entities,
                                       org.springframework.data.jpa.repository.JpaRepository<T, ID> repo) {
        int total = entities.size();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<T> batch = entities.subList(i, Math.min(i + BATCH_SIZE, total));
            repo.saveAll(batch);

            // flush() → sends the SQL for this batch to DB immediately
            // clear() → evicts all entities from session cache → frees memory
            // Without these, all 750K entities accumulate in memory → OutOfMemoryError
            entityManager.flush();
            entityManager.clear();

            log.debug("[Persist] {} saved {}/{}", label, Math.min(i + BATCH_SIZE, total), total);
        }
    }
}
