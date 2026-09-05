package com.eventticket.checkout.usecase;

import com.eventticket.checkout.domain.Order;
import com.eventticket.checkout.repository.OrderRepository;
import com.eventticket.payment.domain.PaymentProvider;
import com.eventticket.payment.domain.PaymentSession;
import com.eventticket.payment.repository.PaymentSessionRepository;
import com.eventticket.shared.UserDirectory;
import com.eventticket.shared.error.ApiException;
import com.eventticket.shared.error.ErrorCodes;
import com.eventticket.shared.tenancy.TenantContext;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * requirements/005 criteria 1 and 11. An Order may have many attempts and at most one success.
 *
 * <p>A new attempt is allowed while the holds are alive and refused once they are not - which
 * is the difference between a buyer whose card was declined trying again, and a buyer paying
 * for seats that now belong to somebody else. The refusal happens here rather than at
 * confirmation, so no money moves for seats that are already gone.
 */
@Component
public class StartPayment {

    private static final Logger log = LoggerFactory.getLogger(StartPayment.class);

    private final OrderRepository orders;
    private final PaymentSessionRepository sessions;
    private final Map<String, PaymentProvider> providers;
    private final UserDirectory users;

    public StartPayment(OrderRepository orders, PaymentSessionRepository sessions,
                 List<PaymentProvider> providers, UserDirectory users) {
        this.orders = orders;
        this.sessions = sessions;
        this.providers = providers.stream()
                .collect(Collectors.toMap(PaymentProvider::name, Function.identity()));
        this.users = users;
    }

    @Transactional
    public PaymentSession start(UUID orderId, String providerName) {
        UUID userId = TenantContext.requireUserId();
        Order order = orders.findOrThrow(orderId);
        if (!order.buyerUserId().equals(userId)) {
            throw ApiException.notFound("Order");
        }
        order.requirePayable();

        PaymentProvider provider = providers.get(providerName);
        if (provider == null) {
            throw new ApiException(ErrorCodes.VALIDATION_FAILED,
                    "There is no payment provider called \"" + providerName + "\".");
        }

        PaymentProvider.Started started = provider.start(new PaymentProvider.Attempt(
                orderId, order.total(), order.holdExpiresAt(), users.emailOf(userId)));

        PaymentSession session = sessions.save(new PaymentSession(orderId, order.organizationId(),
                userId, provider.name(), started.providerRef(), started.nextAction(),
                started.expiresAt()));

        log.info("Started payment sessionId={} orderId={} provider={} nextAction={}",
                session.id(), orderId, provider.name(), started.nextAction().type());

        return session;
    }
}
