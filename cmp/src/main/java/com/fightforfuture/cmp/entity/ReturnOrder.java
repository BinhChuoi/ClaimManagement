package com.fightforfuture.cmp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

@Entity
@Table(name = "return_orders")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnOrder extends BaseEntity implements Persistable<String> {

    // Maps return_order_no → return_order_request_id column (the unique ID we have from Athena)
    @Id
    @Column(name = "return_order_request_id", length = 50)
    private String returnOrderRequestId;

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "invoice_number")
    private InvoiceHeader invoiceHeader;

    @Column(name = "return_order_status", length = 20)
    private String returnOrderStatus;

    @Column(name = "return_order_no", length = 20)
    private String returnOrderNo;

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @PostLoad
    void markNotNew() { this.isNew = false; }

    @Override
    public String getId() { return returnOrderRequestId; }

    @Override
    public boolean isNew() { return isNew; }
}
