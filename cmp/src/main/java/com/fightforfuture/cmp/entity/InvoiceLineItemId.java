package com.fightforfuture.cmp.entity;

import lombok.*;

import java.io.Serializable;

@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@EqualsAndHashCode
public class InvoiceLineItemId implements Serializable {

    /** Mirrors the PK type of {@link InvoiceHeader} — used by the @IdClass mapping. */
    private Long invoiceHeader;

    private String lineItemNo;
}
