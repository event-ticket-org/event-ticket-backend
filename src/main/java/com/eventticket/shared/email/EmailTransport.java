package com.eventticket.shared.email;

/**
 * The thing that actually hands a message to a mail provider, behind an interface so that
 * neither the test suite nor local development depends on one (requirements/006 criterion 7).
 *
 * <p>Distinct from {@link EmailSender}, which is what callers use. A caller asks for a message
 * to be delivered; a transport is one attempt at delivering it, and may fail. Everything about
 * recording that failure and trying again lives between the two.
 *
 * <p>Deliberately thin. Unlike payment providers, mail providers differ in credentials rather
 * than in flow, so there is nothing here to model a session or a next action for.
 */
public interface EmailTransport {

    /** Throws if the message could not be handed over. Failure is recorded, never swallowed. */
    public void deliver(String toAddress, String subject, String body);
}
