package com.fightforfuture.cmp.repository;

import com.fightforfuture.cmp.entity.InvoiceLineItem;
import com.fightforfuture.cmp.entity.InvoiceLineItemId;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface InvoiceLineItemRepository extends JpaRepository<InvoiceLineItem, InvoiceLineItemId> {

    // Insert or skip on duplicate (invoice_number, line_item_no)
    @Modifying
    @Query(value = """
        INSERT INTO invoices_line_items
            (invoice_number, line_item_no, description, item_category,
             invoice_quantity, submitted_quantity, net_value,
             created_at, updated_at, created_by, updated_by)
        VALUES
            (:#{#li.invoiceHeader.invoiceNumber}, :#{#li.lineItemNo},
             :#{#li.description}, :#{#li.itemCategory},
             :#{#li.invoiceQuantity}, :#{#li.submittedQuantity}, :#{#li.netValue},
             NOW(), NOW(), 'system', 'system')
        ON CONFLICT (invoice_number, line_item_no) DO NOTHING
        """, nativeQuery = true)
    void upsert(@Param("li") InvoiceLineItem li);
}
