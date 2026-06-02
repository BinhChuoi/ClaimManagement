package com.fightforfuture.cmp.dto;

import lombok.Builder;
import lombok.Getter;

import java.math.BigDecimal;
import java.time.LocalDate;

/**
 * One flat row returned from v_all_invoices / v_invoice_delta.
 * Each row = one line item with its header and return order data denormalized.
 */
@Getter
@Builder
public class AthenaInvoiceRow {

    // ── Header fields ─────────────────────────────────────────
    private Long    invoiceNumber;
    private String  customerCode;
    private String  currency;
    private LocalDate invoiceDate;
    private LocalDate dueDate;
    private BigDecimal invoiceAmount;
    private String  billingType;
    private String  channel;
    private String  salesOrg;
    private String  countryCode;

    // ── Line item fields ──────────────────────────────────────
    private String  partNumber;
    private String  lineItemNo;
    private String  boschMaterial;
    private String  customerMaterial;
    private String  description;
    private String  itemCategory;
    private BigDecimal invoiceQuantity;
    private BigDecimal submittedQuantity;
    private BigDecimal netValue;

    // ── Return order fields (nullable — LEFT JOIN) ────────────
    private String  claimNumber;
    private String  returnOrderNo;
    private String  returnOrderStatus;
    private String  returnOrderSapType;
    private Integer returnOrderPhysicalReturn;
}
