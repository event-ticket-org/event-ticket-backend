package com.eventticket.checkout.usecase;

import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.payment.domain.Refund;
import com.eventticket.payment.repository.RefundRepository;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * The refund attempts against one Order, newest first.
 *
 * <p>The Order is loaded first and that is not a formality: {@code refund} carries no
 * row-level security - a provider's callback has no tenant with which to satisfy one - so the
 * Order is where "may this caller see this?" is answered, by the policy that admits its buyer
 * or the Organization selling it and nobody else.
 *
 * <p>Several rows are the normal case rather than an error. A refund the provider refused is
 * kept and a second attempt made beside it, so the history is what says whether the money ever
 * actually moved.
 */
@Component
public class ListOrderRefunds {

    private final OrderRepository orders;
    private final RefundRepository refunds;

    public ListOrderRefunds(OrderRepository orders, RefundRepository refunds) {
        this.orders = orders;
        this.refunds = refunds;
    }

    @Transactional(readOnly = true)
    public List<Refund> list(UUID orderId) {
        orders.findOrThrow(orderId)
                .requireBuyerOrOrganization(TenantContext.requireUserId(), TenantContext.organizationId());
        return refunds.findByOrderIdOrderByCreatedAtDesc(orderId);
    }
}
