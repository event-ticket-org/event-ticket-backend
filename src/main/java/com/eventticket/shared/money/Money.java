package com.eventticket.shared.money;

/**
 * An amount in the currency's smallest unit.
 *
 * <p>An integer of minor units, never a {@code double} and never a bare number. VND has no
 * minor unit, so the amount is the dong - which is exactly why the currency travels with it:
 * a bare 50000 is unreadable the moment a second currency exists, and nfr.md already commits
 * to displaying prices in the Venue's locale.
 *
 * <p>Lives in {@code shared} because amounts cross module boundaries: an Order in
 * {@code checkout} is priced from a tier in {@code event} and settled in {@code payment}.
 */
public record Money(long amount, Currency currency) {

    /** Mirrors the contract's {@code Money.currency} enum, which v1 closes at one value. */
    public enum Currency { VND }

    public Money {
        if (amount < 0) {
            throw new IllegalArgumentException("A price cannot be negative: " + amount);
        }
    }

    public static Money vnd(long amount) {
        return new Money(amount, Currency.VND);
    }
}
