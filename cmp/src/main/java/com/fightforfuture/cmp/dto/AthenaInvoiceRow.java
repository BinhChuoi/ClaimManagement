package com.fightforfuture.cmp.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

@Getter
@Builder
public class AthenaInvoiceRow {

    // ── Header ────────────────────────────────────────────────
    private Long      invoiceNumber;
    private String    customerCode;
    private String    currency;
    private LocalDate invoiceDate;
    private BigDecimal invoiceAmount;
    private String    billingType;
    private String    channel;
    private String    salesOrg;
    private String    countryCode;

    // ── Line item ─────────────────────────────────────────────
    private String    partNumber;
    private String    lineItemNo;
    private String    boschMaterial;
    private String    customerMaterial;
    private String    description;
    private String    itemCategory;
    private BigDecimal invoiceQuantity;
    private BigDecimal submittedQuantity;
    private BigDecimal netValue;
}
