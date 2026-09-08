package com.eventticket.support;

import com.eventticket.shared.email.EmailTransport;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Captures email instead of sending it, so that tests can follow a verification link the way a
 * person would rather than reaching into the token table behind the flow's back.
 *
 * <p>A transport rather than an {@code EmailSender}: replacing the sender would skip the outbox
 * entirely, and the outbox is where requirements/006 criterion 8 lives. Everything a test sees
 * here has been through a real {@code email_delivery} row.
 *
 * <p>{@link #failNext} makes the transport throw, which is how failure and retry get tested at
 * all - a transport that cannot fail proves nothing about the handling of one that does.
 */
public class RecordingEmailSender implements EmailTransport {

    public record Message(String to, String subject, String body) {}

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private final List<Message> sent = new ArrayList<>();
    private int failuresRemaining;

    @Override
    public synchronized void deliver(String toAddress, String subject, String body) {
        if (failuresRemaining > 0) {
            failuresRemaining--;
            throw new IllegalStateException("the mail provider is having a bad day");
        }
        sent.add(new Message(toAddress, subject, body));
    }

    /** The next {@code count} delivery attempts throw, as a provider outage would. */
    public synchronized void failNext(int count) {
        this.failuresRemaining = count;
    }

    public synchronized List<Message> sent() {
        return List.copyOf(sent);
    }

    public synchronized void clear() {
        sent.clear();
        failuresRemaining = 0;
    }

    public synchronized List<Message> to(String address) {
        return sent.stream().filter(m -> m.to().equalsIgnoreCase(address)).toList();
    }

    /** The verification token from the most recent email to this address. */
    public synchronized Optional<String> verificationTokenFor(String toAddress) {
        return tokenFrom(toAddress, "Confirm your email address");
    }

    /**
     * The reset token from the most recent reset email to this address.
     *
     * <p>Selected by subject rather than by being the latest link, because an account being
     * recovered receives two emails in quick succession and only one of them carries a link.
     */
    public synchronized Optional<String> resetTokenFor(String toAddress) {
        return tokenFrom(toAddress, "Reset your password");
    }

    private synchronized Optional<String> tokenFrom(String toAddress, String subject) {
        return sent.reversed().stream()
                .filter(m -> m.to().equalsIgnoreCase(toAddress))
                .filter(m -> m.subject().equals(subject))
                .map(m -> TOKEN.matcher(m.body()))
                .filter(Matcher::find)
                .map(m -> m.group(1))
                .findFirst();
    }

    @TestConfiguration(proxyBeanMethods = false)
    public static class Config {
        @Bean
        @Primary
        public RecordingEmailSender recordingEmailSender() {
            return new RecordingEmailSender();
        }
    }
}
