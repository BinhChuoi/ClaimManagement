package com.fightforfuture.cmp.repository;

import com.fightforfuture.cmp.entity.ReturnOrder;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.Optional;
import java.util.UUID;

@Repository
public interface ReturnOrderRepository extends JpaRepository<ReturnOrder, UUID> {

    Optional<ReturnOrder> findByReturnOrderRequestId(String returnOrderRequestId);

    boolean existsByReturnOrderRequestId(String returnOrderRequestId);
}
