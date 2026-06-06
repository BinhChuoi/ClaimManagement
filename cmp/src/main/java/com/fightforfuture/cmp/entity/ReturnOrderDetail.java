package com.fightforfuture.cmp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.UuidGenerator;

import java.util.UUID;

@Entity
@Table(
    name = "cc_return_order_detail",
    indexes = {
        @Index(name = "idx_rod_return_order_id", columnList = "return_order_id"),
        @Index(name = "idx_rod_link_id",         columnList = "link_id"),
    }
)
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ReturnOrderDetail extends BaseEntity {

    @Id
    @UuidGenerator(style = UuidGenerator.Style.VERSION_7)
    @Column(name = "id", columnDefinition = "uuid")
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_order_id", nullable = false)
    private ReturnOrder returnOrder;

    /** Claim item / part ID this return order line covers */
    @Column(name = "link_id", nullable = false, length = 100)
    private String linkId;
}
