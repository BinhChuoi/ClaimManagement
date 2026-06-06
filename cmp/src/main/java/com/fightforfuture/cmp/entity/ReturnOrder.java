package com.fightforfuture.cmp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(
    name = "cc_return_order",
    indexes = {
        @Index(name = "idx_ro_request_id", columnList = "return_order_request_id"),
        @Index(name = "idx_ro_claim_id",   columnList = "claim_id"),
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnOrder extends BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    /** Idempotency key sent to SAP as PurchaseOrderNo — e.g. CLM123-0001 */
    @Column(name = "return_order_request_id", nullable = false, unique = true, length = 100)
    private String returnOrderRequestId;

    /** SAP-assigned VBELN, populated after successful creation */
    @Column(name = "return_order_no", length = 20)
    private String returnOrderNo;

    @Column(name = "return_order_status", nullable = false, length = 20)
    private String returnOrderStatus;          // Success / Failed / Completed

    @Column(name = "return_order_sap_type", length = 10)
    private String returnOrderSapType;         // YCC1, YCH1, YCR1, YCF1

    @Column(name = "return_order_sap_reason", length = 20)
    private String returnOrderSapReason;

    @Column(name = "return_order_type", length = 20)
    private String returnOrderType;            // invoice / order

    @Column(name = "return_order_error_msg", columnDefinition = "TEXT")
    private String returnOrderErrorMsg;

    @Column(name = "claim_id", nullable = false, length = 100)
    private String claimId;

    @Column(name = "shipping_method", length = 10)
    private String shippingMethod;

    /** Raw JSON request payload sent to SAP */
    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @OneToMany(
        mappedBy      = "returnOrder",
        cascade       = CascadeType.ALL,
        orphanRemoval = true,
        fetch         = FetchType.LAZY
    )
    @Builder.Default
    private List<ReturnOrderDetail> details = new ArrayList<>();
}
