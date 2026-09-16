package com.eventticket.event.domain;

import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import org.hibernate.annotations.Immutable;

/**
 * What kind of thing an Event is (KB requirements/009 criterion 12). Every Event has exactly
 * one, chosen from a set the platform defines.
 *
 * <p>It is the one attribute of an Event whose permitted values belong to nobody who owns the
 * Event, which is what makes it comparable across Organizations and the whole reason the
 * listing can group by it. An Organization choosing from the set is the point; an Organization
 * extending it would end the comparison.
 *
 * <p>The set carries a catch-all, and an Event sitting in it forever is a correct outcome
 * rather than an unfinished one. Without it the taxonomy would have to be either complete - a
 * claim about events nobody has thought of yet - or optional, which puts every Event that
 * skipped the question into a bucket the listing cannot show.
 *
 * <p>{@code @Immutable} for the same reason as {@link com.eventticket.venue.domain.City}: V14
 * revokes the application role's write privileges, so criterion 12 is a fact about the
 * database rather than a convention the code observes.
 */
@Entity
@Immutable
@Table(name = "event_category")
public class EventCategory {

    /** What an Event predating the taxonomy is given, and what an Event fitting nothing else keeps. */
    public static final String CATCH_ALL = "khac";

    @Id
    private String slug;

    private String name;

    private int position;

    protected EventCategory() {}

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
