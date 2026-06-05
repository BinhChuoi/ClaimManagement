package com.fightforfuture.cmp.service;

import com.fightforfuture.cmp.dto.AthenaInvoiceRow;
import com.fightforfuture.cmp.entity.InvoiceHeader;
import com.fightforfuture.cmp.entity.InvoiceLineItem;
import com.fightforfuture.cmp.mapper.InvoiceMapper;
import com.fightforfuture.cmp.repository.InvoiceHeaderRepository;
import com.fightforfuture.cmp.repository.InvoiceLineItemRepository;
import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.Comparator;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class InvoicePersistenceService {

    private static final int BATCH_SIZE = 500;

    private final InvoiceHeaderRepository   invoiceHeaderRepository;
    private final InvoiceLineItemRepository invoiceLineItemRepository;
    private final InvoiceMapper             invoiceMapper;

    @PersistenceContext
    private EntityManager entityManager;

    @Transactional
    public int save(List<AthenaInvoiceRow> rows) {
        if (rows.isEmpty()) return 0;

        // ── 1. Deduplicate headers ────────────────────────────
        Map<Long, InvoiceHeader> headerMap = new LinkedHashMap<>();
        for (AthenaInvoiceRow row : rows) {
            headerMap.computeIfAbsent(row.getInvoiceNumber(), id -> invoiceMapper.toHeader(row));
        }

        // ── 2. Upsert headers sorted by invoice_number (prevents deadlocks) ──
        List<InvoiceHeader> sortedHeaders = headerMap.values().stream()
                .sorted(Comparator.comparing(InvoiceHeader::getInvoiceNumber))
                .collect(Collectors.toList());
        upsertHeaders(sortedHeaders);

        // ── 3. Save line items ────────────────────────────────
        List<InvoiceLineItem> lineItems = rows.stream()
                .map(row -> invoiceMapper.toLineItem(row, headerMap.get(row.getInvoiceNumber())))
                .collect(Collectors.toList());
        saveInBatches(lineItems);

        log.info("[Persist] Saved {} headers, {} line items", headerMap.size(), lineItems.size());
        return lineItems.size();
    }

    private void upsertHeaders(List<InvoiceHeader> headers) {
        int total = headers.size();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<InvoiceHeader> batch = headers.subList(i, Math.min(i + BATCH_SIZE, total));
            batch.forEach(invoiceHeaderRepository::upsert);
            entityManager.flush();
            entityManager.clear();
        }
    }

    private void saveInBatches(List<InvoiceLineItem> entities) {
        int total = entities.size();
        for (int i = 0; i < total; i += BATCH_SIZE) {
            List<InvoiceLineItem> batch = entities.subList(i, Math.min(i + BATCH_SIZE, total));
            invoiceLineItemRepository.saveAll(batch);
            entityManager.flush();
            entityManager.clear();
        }
    }
}
