package com.fightforfuture.cmp.repository;

import com.fightforfuture.cmp.entity.ReturnOrderDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.UUID;

@Repository
public interface ReturnOrderDetailRepository extends JpaRepository<ReturnOrderDetail, UUID> {

    List<ReturnOrderDetail> findByReturnOrderId(UUID returnOrderId);
}
