package com.fightforfuture.cmp.repository;

import com.fightforfuture.cmp.entity.InvoiceHeader;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface InvoiceHeaderRepository extends JpaRepository<InvoiceHeader, Long> {

    // Upsert: insert or update on duplicate invoice_number
    @Modifying
    @Query(value = """
        INSERT INTO invoices_header
            (invoice_number, customer_code, currency, invoice_date, invoice_amount,
             sales_org, country_code, created_at, updated_at, created_by, updated_by)
        VALUES
            (:#{#h.invoiceNumber}, :#{#h.customerCode}, :#{#h.currency},
             :#{#h.invoiceDate}, :#{#h.invoiceAmount}, :#{#h.salesOrg},
             :#{#h.countryCode}, NOW(), NOW(), 'system', 'system')
        ON CONFLICT (invoice_number) DO UPDATE SET
            customer_code  = EXCLUDED.customer_code,
            currency       = EXCLUDED.currency,
            invoice_date   = EXCLUDED.invoice_date,
            invoice_amount = EXCLUDED.invoice_amount,
            sales_org      = EXCLUDED.sales_org,
            country_code   = EXCLUDED.country_code,
            updated_at     = NOW()
        """, nativeQuery = true)
    void upsert(@Param("h") InvoiceHeader h);
}
