package com.fightforfuture.cmp.batch;

import com.fightforfuture.cmp.dto.AthenaInvoiceRow;
import com.fightforfuture.cmp.service.InvoicePersistenceService;
import lombok.RequiredArgsConstructor;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.stereotype.Component;

import java.util.ArrayList;

@Component
@RequiredArgsConstructor
public class InvoiceCompositeItemWriter implements ItemWriter<AthenaInvoiceRow> {

    private final InvoicePersistenceService persistenceService;

    @Override
    public void write(Chunk<? extends AthenaInvoiceRow> chunk) {
        persistenceService.save(new ArrayList<>(chunk.getItems()));
    }
}
