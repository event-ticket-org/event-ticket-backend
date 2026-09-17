package com.eventticket.event.search;

/**
 * The search cluster could not be reached, or refused the work.
 *
 * <p>Unchecked and deliberately not an {@code ApiException}: nothing a visitor did caused it and
 * there is no error code that would help them. What reads it is the indexer, which retries, and
 * the listing, which falls back to Postgres (requirements/009 criterion 20).
 */
public class SearchUnavailableException extends RuntimeException {

    public SearchUnavailableException(String message, Throwable cause) {
        super(message, cause);
    }
}
