package com.fightforfuture.cmp.repository;

import com.fightforfuture.cmp.entity.ReturnOrderDetail;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import java.util.List;

@Repository
public interface ReturnOrderDetailRepository extends JpaRepository<ReturnOrderDetail, Long> {

    List<ReturnOrderDetail> findByReturnOrderId(Long returnOrderId);
}
