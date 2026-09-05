package com.eventticket.support;

import com.eventticket.shared.EmailSender;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;

/**
 * Captures email instead of sending it, so that tests can follow a verification link the way
 * a person would rather than reaching into the token table behind the flow's back.
 */
public class RecordingEmailSender implements EmailSender {

    public record Message(String to, String subject, String body) {}

    private static final Pattern TOKEN = Pattern.compile("token=([A-Za-z0-9_-]+)");

    private final List<Message> sent = new ArrayList<>();

    @Override
    public synchronized void send(String toAddress, String subject, String body) {
        sent.add(new Message(toAddress, subject, body));
    }

    public synchronized List<Message> sent() {
        return List.copyOf(sent);
    }

    public synchronized void clear() {
        sent.clear();
    }

    /** The verification token from the most recent email to this address. */
    public synchronized Optional<String> verificationTokenFor(String toAddress) {
        return sent.reversed().stream()
                .filter(m -> m.to().equalsIgnoreCase(toAddress))
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
