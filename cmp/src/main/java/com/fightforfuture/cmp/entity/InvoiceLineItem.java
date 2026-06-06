package com.fightforfuture.cmp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;

@Entity
@Table(name = "invoices_line_items")
@IdClass(InvoiceLineItemId.class)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceLineItem extends BaseEntity implements Persistable<InvoiceLineItemId> {

    @Id
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_number", nullable = false)
    private InvoiceHeader invoiceHeader;

    @Id
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

    // ── Persistable — skip SELECT before INSERT since key comes from source data ──

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @PostLoad
    void markNotNew() { this.isNew = false; }

    @Override
    public InvoiceLineItemId getId() {
        return new InvoiceLineItemId(
                invoiceHeader != null ? invoiceHeader.getInvoiceNumber() : null,
                lineItemNo
        );
    }

    @Override
    public boolean isNew() { return isNew; }
}
