package com.eventticket.platform.domain;

import com.eventticket.organization.domain.Organization;
import com.eventticket.shared.DirectoryUser;
import java.util.List;

/**
 * An Organization and what an administrator needs in order to decide about it
 * (requirements/001 criterion 14).
 *
 * <p>The Organization on its own is a name, a status and a date, which is what the approval
 * queue used to show. Approving decides who may sell tickets to the public on a page this
 * platform endorses, and nobody can decide that from a name - so the people accountable for it
 * travel with it, and the use case that assembles them is the only place that knows how.
 */
public record OrganizationReview(Organization organization, List<DirectoryUser> owners) {
}
