package com.fightforfuture.cmp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.math.BigDecimal;

@Entity
@Table(name = "invoices_line_items")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceLineItem extends BaseEntity {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_number", nullable = false)
    private InvoiceHeader invoiceHeader;

    @Column(name = "line_item_no", length = 10)
    private String lineItemNo;

    @Column(name = "description", length = 100)
    private String description;

    @Column(name = "item_category", length = 10)
    private String itemCategory;

    @Column(name = "invoice_quantity", precision = 10, scale = 3)
    private BigDecimal invoiceQuantity;

    @Column(name = "submitted_quantity", precision = 10, scale = 3)
    private BigDecimal submittedQuantity;

    @Column(name = "net_value", precision = 15, scale = 2)
    private BigDecimal netValue;
}
