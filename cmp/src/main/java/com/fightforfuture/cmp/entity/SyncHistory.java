package com.fightforfuture.cmp.entity;

import jakarta.persistence.*;
import lombok.*;

import java.time.Instant;

@Entity
@Table(name = "sync_history")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SyncHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "from_invoice_number", nullable = false)
    private Long fromInvoiceNumber;         // where this run started

    @Column(name = "to_invoice_number")
    private Long toInvoiceNumber;           // highest invoice number pulled (set on completion)

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private SyncStatus status;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt;

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "records_synced")
    private Integer recordsSynced;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;
}
