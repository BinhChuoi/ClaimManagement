package com.fightforfuture.cmp.entity;

import jakarta.persistence.*;
import lombok.*;
import org.springframework.data.domain.Persistable;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

@Entity
@Table(name = "invoices_header")
@Getter
@Setter
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class InvoiceHeader extends BaseEntity implements Persistable<Long> {

    @Id
    @Column(name = "invoice_number")
    private Long invoiceNumber;

    @Column(name = "customer_code", length = 20)
    private String customerCode;

    @Column(name = "currency", length = 3)
    private String currency;

    @Column(name = "invoice_date")
    private LocalDate invoiceDate;

    @Column(name = "invoice_amount", precision = 15, scale = 2)
    private BigDecimal invoiceAmount;

    @Column(name = "sales_org", length = 10)
    private String salesOrg;

    @Column(name = "country_code", length = 5)
    private String countryCode;

    @OneToMany(
        mappedBy      = "invoiceHeader",
        cascade       = CascadeType.ALL,
        orphanRemoval = true,
        fetch         = FetchType.LAZY
    )
    @Builder.Default
    private List<InvoiceLineItem> lineItems = new ArrayList<>();

    @Transient
    @Builder.Default
    private boolean isNew = true;

    @PostLoad
    void markNotNew() { this.isNew = false; }

    @Override
    public Long getId() { return invoiceNumber; }

    @Override
    public boolean isNew() { return isNew; }
}
