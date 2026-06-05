package com.fightforfuture.cmp.batch;

import com.fightforfuture.cmp.dto.AthenaInvoiceRow;
import lombok.extern.slf4j.Slf4j;
import org.springframework.batch.infrastructure.item.ItemProcessor;
import org.springframework.stereotype.Component;

@Slf4j
@Component
public class InvoiceItemProcessor implements ItemProcessor<AthenaInvoiceRow, AthenaInvoiceRow> {

    @Override
    public AthenaInvoiceRow process(AthenaInvoiceRow item) {
        if (item.getInvoiceNumber() == null) {
            log.warn("[InvoiceItemProcessor] Skipping row with null invoice_number");
            return null;
        }
        return item;
    }
}
