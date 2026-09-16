package com.eventticket.venue.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * A city a Venue may be in (KB requirements/009 criterion 13).
 *
 * <p>It was free text on the Venue until the listing began grouping and counting by it.
 * Filtering tolerates two spellings of Hà Nội and returns slightly wrong results; counting
 * reports two cities.
 *
 * <p>The slug is the identity, not a surrogate id: it is what the contract exposes, what a URL
 * carries and what a client sends back, and it is stable by definition - renaming a City
 * changes {@code name} and never this.
 *
 * <p>{@code @Immutable} because the application cannot write this table at all. V14 revokes
 * INSERT, UPDATE and DELETE from the application role, so the set is the platform's and an
 * attempt to extend it fails in Postgres rather than in a code review. This annotation is how
 * Hibernate is told the same thing, and is the reason it never tries.
 */
@Entity
@Immutable
@Table(name = "city")
public class City {

    @Id
    private String slug;

    private String name;

    private int position;

    protected City() {}

    public String slug() {
        return slug;
    }

    public String name() {
        return name;
    }

    public int position() {
        return position;
    }
}
