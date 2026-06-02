package com.fightforfuture.cmp.repository;

import com.fightforfuture.cmp.entity.ReturnOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

@Repository
public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, String> {

    @Modifying
    @Query(value = """
        INSERT INTO return_orders
            (return_order_request_id, invoice_number, return_order_status,
             return_order_no, created_at, updated_at, created_by, updated_by)
        VALUES
            (:#{#r.returnOrderRequestId},
             :#{#r.invoiceHeader != null ? #r.invoiceHeader.invoiceNumber : null},
             :#{#r.returnOrderStatus}, :#{#r.returnOrderNo},
             NOW(), NOW(), 'system', 'system')
        ON CONFLICT (return_order_request_id) DO UPDATE SET
            return_order_status = EXCLUDED.return_order_status,
            return_order_no     = EXCLUDED.return_order_no,
            updated_at          = NOW()
        """, nativeQuery = true)
    void upsert(@Param("r") ReturnOrder r);
}
