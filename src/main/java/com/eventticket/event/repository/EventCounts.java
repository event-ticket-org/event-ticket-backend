package com.eventticket.event.repository;

import com.eventticket.shared.money.Money;
import java.util.UUID;

/**
 * The result of {@link EventQueries#countsFor}.
 *
 * <p>A class rather than the Spring Data interface projection it used to be. An interface
 * projection is backed by the column names a SQL result set carries; an aggregation returns
 * documents, which map onto fields. The getters keep their names so that nothing above the
 * repository changes.
 *
 * <p>Worth noting what is lost in the swap: the JPQL constructor expression was checked when
 * the application started, so a renamed column failed at boot. A pipeline is checked when it
 * runs, and a field renamed in one place and not the other produces zeroes rather than an
 * error. That is a general property of the migration, not a fact about this class.
 */
public class EventCounts {

    private UUID eventId;
    private long sold;
    private long refundRequired;
    private long salesAmount;
    private String salesCurrency;

    public UUID getEventId() {
        return eventId;
    }

    public long getSold() {
        return sold;
    }

    public long getRefundRequired() {
        return refundRequired;
    }

    public long getSalesTotal() {
        return salesAmount;
    }

    /** Null when the Event has sold nothing, because there is then no Order to take it from. */
    public String getSalesCurrency() {
        return salesCurrency;
    }

    /**
     * The money as a {@link Money}, with the null currency handled once here rather than at
     * each of the four call sites. An Event that has sold nothing has taken zero, and zero of
     * no particular currency is still zero dong in a system whose contract closes the enum at
     * one value.
     */
    public Money salesTotal() {
        return new Money(salesAmount, salesCurrency == null
                ? Money.Currency.VND
                : Money.Currency.valueOf(salesCurrency));
    }
}
