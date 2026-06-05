package com.fightforfuture.cmp.service;

import com.fightforfuture.cmp.dto.AthenaInvoiceRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.util.List;

@Component
@RequiredArgsConstructor
public class InvoiceJobProcessor implements JobProcessor {

    static final String JOB_TYPE = "INVOICE";

    private final AthenaService             athenaService;
    private final InvoicePersistenceService persistenceService;

    @Override
    public String getJobType() {
        return JOB_TYPE;
    }

    @Override
    public int process(LocalDate startDate, LocalDate endDate) {
        List<AthenaInvoiceRow> rows = athenaService.fetchByMonth(
                startDate.getYear(), startDate.getMonthValue());
        return persistenceService.save(rows);
    }
}
