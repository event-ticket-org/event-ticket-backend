package com.eventticket.checkout.repository;

import com.eventticket.checkout.domain.OrderSeat;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderSeatRepository extends JpaRepository<OrderSeat, UUID> {

    public List<OrderSeat> findByOrderIdOrderByLabelAsc(UUID orderId);

    public List<OrderSeat> findByOrderIdIn(List<UUID> orderIds);

    public long countByOrderId(UUID orderId);
}
