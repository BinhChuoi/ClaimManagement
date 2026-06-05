package com.fightforfuture.cmp.entity;

import com.fightforfuture.cmp.util.SnowflakeId;
import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

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
public class ReturnOrderDetail extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "id")
    private Long id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_order_id", nullable = false)
    private ReturnOrder returnOrder;

    /** Claim item / part ID this return order line covers */
    @Column(name = "link_id", nullable = false, length = 100)
    private String linkId;

    // ── Persistable ───────────────────────────────────────────────────────────

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @PostLoad
    void markNotNew() { this.isNew = false; }

    @PrePersist
    void assignId() {
        if (id == null) id = SnowflakeId.next();
    }

    @Override public Long getId()    { return id; }
    @Override public boolean isNew() { return isNew; }
}
