package com.fightforfuture.cmp.repository;

import com.fightforfuture.cmp.entity.ReturnOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;

@Repository
public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, Long> {

    Optional<ReturnOrder> findByReturnOrderRequestId(String returnOrderRequestId);

    boolean existsByReturnOrderRequestId(String returnOrderRequestId);
}
