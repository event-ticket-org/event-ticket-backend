package com.eventticket.payment.domain;

/**
 * What the buyer's client must do next, said in a way that does not name a provider.
 *
 * <p>This type is the abstraction (KB ADR-0002). VNPay redirects, PayOS and SePay show a
 * VietQR and confirm by webhook, Stripe hosts a checkout page - they differ in flow, not
 * merely in credentials, so the thing that varies has to be the *shape of what happens next*
 * rather than a set of arguments to a charge method.
 *
 * <p>{@code reference} exists because of VietQR specifically: a bank transfer carries a memo
 * the buyer must include, and there is nowhere else in a redirect-shaped model to put it.
 */
public record NextAction(Type type, String url, String qrPayload, String reference) {

    public enum Type { REDIRECT, DISPLAY_QR, HOSTED_CHECKOUT, NONE }

    public static NextAction redirect(String url) {
        return new NextAction(Type.REDIRECT, url, null, null);
    }

    public static NextAction hostedCheckout(String url) {
        return new NextAction(Type.HOSTED_CHECKOUT, url, null, null);
    }

    public static NextAction displayQr(String payload, String reference) {
        return new NextAction(Type.DISPLAY_QR, null, payload, reference);
    }
}
