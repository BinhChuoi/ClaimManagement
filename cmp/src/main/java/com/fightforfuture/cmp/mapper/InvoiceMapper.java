package com.fightforfuture.cmp.mapper;

import com.fightforfuture.cmp.dto.AthenaInvoiceRow;
import com.fightforfuture.cmp.entity.InvoiceHeader;
import com.fightforfuture.cmp.entity.InvoiceLineItem;
import org.springframework.stereotype.Component;

@Component
public class InvoiceMapper {

    public InvoiceHeader toHeader(AthenaInvoiceRow row) {
        return InvoiceHeader.builder()
                .invoiceNumber(row.getInvoiceNumber())
                .customerCode(row.getCustomerCode())
                .currency(row.getCurrency())
                .invoiceDate(row.getInvoiceDate())
                .invoiceAmount(row.getInvoiceAmount())
                .salesOrg(row.getSalesOrg())
                .countryCode(row.getCountryCode())
                .build();
    }

    public InvoiceLineItem toLineItem(AthenaInvoiceRow row, InvoiceHeader header) {
        return InvoiceLineItem.builder()
                .invoiceHeader(header)
                .lineItemNo(row.getLineItemNo())
                .description(row.getDescription())
                .itemCategory(row.getItemCategory())
                .invoiceQuantity(row.getInvoiceQuantity())
                .submittedQuantity(row.getSubmittedQuantity())
                .netValue(row.getNetValue())
                .build();
    }
}
