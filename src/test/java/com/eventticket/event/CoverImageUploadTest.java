package com.eventticket.event;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.CoverConfirmation;
import com.eventticket.api.model.CoverUpload;
import com.eventticket.api.model.Error;
import com.eventticket.api.model.Event;
import com.eventticket.api.model.PublicEvent;
import com.eventticket.api.model.SeatMap;
import com.eventticket.api.model.TokenPair;
import com.eventticket.api.model.Venue;
import com.eventticket.support.ApiTest;
import com.eventticket.support.SeatMaps;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import javax.imageio.ImageIO;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;

/**
 * requirements/003 criteria 17-21, ADR-0006.
 *
 * <p>Every test here performs a real upload against a real object store, because the two things
 * most likely to be wrong cannot be checked any other way. A signature is either exactly right
 * or completely broken and reading it proves neither; and a condition in an upload policy is
 * enforced by the store, so the only way to know the ceiling is real is to exceed it.
 */
class CoverImageUploadTest extends ApiTest {

    private static final HttpClient DIRECT = HttpClient.newHttpClient();

    @Test
    @DisplayName("an image is uploaded straight to storage and becomes the event's cover")
    void aCoverIsUploadedAndAdopted() {
        TokenPair manager = approvedManager();
        Event event = draftEvent(manager);

        CoverUpload upload = beginUpload(manager, event.getId()).getBody();
        assertThat(upload.getUploadId()).isNotBlank();
        assertThat(upload.getFields()).containsKeys("key", "policy", "x-amz-signature");
        assertThat(upload.getMaxBytes()).isPositive();

        assertThat(uploadTo(upload, png(), "cover.png")).isBetween(200, 299);

        Event withCover = confirm(manager, event.getId(), upload.getUploadId(), "A crowd at dusk")
                .getBody();

        assertThat(withCover.getCoverImageUrl()).isNotNull();
        assertThat(withCover.getCoverImageUrl().toString()).contains("covers/").endsWith(".png");
        assertThat(withCover.getCoverImageAlt()).isEqualTo("A crowd at dusk");

        // Served, not merely recorded: the URL in the response is one a browser can fetch.
        assertThat(fetch(withCover.getCoverImageUrl())).isEqualTo(png());
    }

    @Test
    @DisplayName("the cover reaches the public page, alt text and all")
    void aCoverIsPublic() {
        TokenPair manager = approvedManager();
        Event event = publishedEvent(manager);
        CoverUpload upload = beginUpload(manager, event.getId()).getBody();
        uploadTo(upload, png(), "cover.png");
        confirm(manager, event.getId(), upload.getUploadId(), "A crowd at dusk");

        PublicEvent page = exchange(HttpMethod.GET, "/public/events/" + event.getId(), null, null,
                PublicEvent.class).getBody();

        assertThat(page.getCoverImageUrl()).isNotNull();
        assertThat(page.getCoverImageAlt()).isEqualTo("A crowd at dusk");
    }

    /**
     * The check that direct-to-storage uploading exists to make necessary. A client says what
     * it is sending and the store believes it; the bytes are the only thing that knows.
     */
    @Test
    @DisplayName("a file that is not an image is refused and not kept")
    void aFileThatIsNotAnImageIsRefused() {
        TokenPair manager = approvedManager();
        Event event = draftEvent(manager);

        CoverUpload upload = beginUpload(manager, event.getId()).getBody();
        // Declared as a PNG, and it is not one.
        uploadTo(upload, "MZ this is an executable, honestly".getBytes(StandardCharsets.UTF_8),
                "cover.png");

        var refused = exchange(HttpMethod.PUT, "/events/" + event.getId() + "/cover", manager,
                new CoverConfirmation(upload.getUploadId()), Error.class);

        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode().getValue()).isEqualTo("COVER_NOT_AN_IMAGE");
        assertThat(coverOf(manager, event.getId())).isNull();

        // And the file is gone rather than sitting in the bucket at a key its uploader knows.
        var second = exchange(HttpMethod.PUT, "/events/" + event.getId() + "/cover", manager,
                new CoverConfirmation(upload.getUploadId()), Error.class);
        assertThat(second.getBody().getCode().getValue()).isEqualTo("COVER_NOT_UPLOADED");
    }

    @Test
    @DisplayName("the store refuses a file past the ceiling, so the ceiling is not ours to trust")
    void tooLargeIsRefusedByTheStore() {
        TokenPair manager = approvedManager();
        Event event = draftEvent(manager);
        CoverUpload upload = beginUpload(manager, event.getId()).getBody();

        byte[] tooBig = new byte[Math.toIntExact(upload.getMaxBytes()) + 1024];
        assertThat(uploadTo(upload, tooBig, "cover.png")).isBetween(400, 499);

        // Nothing landed, so there is nothing to confirm.
        var refused = exchange(HttpMethod.PUT, "/events/" + event.getId() + "/cover", manager,
                new CoverConfirmation(upload.getUploadId()), Error.class);
        assertThat(refused.getBody().getCode().getValue()).isEqualTo("COVER_NOT_UPLOADED");
    }

    @Test
    @DisplayName("confirming an upload nobody made is refused")
    void anInventedUploadIsRefused() {
        TokenPair manager = approvedManager();
        Event event = draftEvent(manager);

        var refused = exchange(HttpMethod.PUT, "/events/" + event.getId() + "/cover", manager,
                new CoverConfirmation(UUID.randomUUID().toString()), Error.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode().getValue()).isEqualTo("COVER_NOT_UPLOADED");

        // A key is built from the ids on a checked request, so an upload id carrying a path is
        // refused before it is ever concatenated into one.
        var traversal = exchange(HttpMethod.PUT, "/events/" + event.getId() + "/cover", manager,
                new CoverConfirmation("../../../etc/passwd"), Error.class);
        assertThat(traversal.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(traversal.getBody().getCode().getValue()).isEqualTo("COVER_NOT_UPLOADED");
    }

    @Test
    @DisplayName("replacing a cover removes the one it replaced")
    void replacingDeletesTheOldFile() {
        TokenPair manager = approvedManager();
        Event event = draftEvent(manager);

        CoverUpload first = beginUpload(manager, event.getId()).getBody();
        uploadTo(first, png(), "one.png");
        URI original = confirm(manager, event.getId(), first.getUploadId(), "First")
                .getBody().getCoverImageUrl();

        CoverUpload second = beginUpload(manager, event.getId()).getBody();
        uploadTo(second, png(), "two.png");
        URI replacement = confirm(manager, event.getId(), second.getUploadId(), "Second")
                .getBody().getCoverImageUrl();

        // A new URL, so a cache that had the old one is not showing a picture nobody chose.
        assertThat(replacement).isNotEqualTo(original);
        assertThat(fetch(replacement)).isEqualTo(png());
        assertThat(statusOf(original)).isEqualTo(404);
    }

    @Test
    @DisplayName("removing a cover deletes the file, and removing nothing is not an error")
    void removingClearsTheEventAndTheStore() {
        TokenPair manager = approvedManager();
        Event event = draftEvent(manager);

        CoverUpload upload = beginUpload(manager, event.getId()).getBody();
        uploadTo(upload, png(), "cover.png");
        URI url = confirm(manager, event.getId(), upload.getUploadId(), "A crowd").getBody()
                .getCoverImageUrl();

        assertThat(exchange(HttpMethod.DELETE, "/events/" + event.getId() + "/cover", manager,
                null, Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);

        assertThat(coverOf(manager, event.getId())).isNull();
        assertThat(statusOf(url)).isEqualTo(404);

        // Asking for no cover when there is no cover is the outcome the caller wanted.
        assertThat(exchange(HttpMethod.DELETE, "/events/" + event.getId() + "/cover", manager,
                null, Void.class).getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
    }

    @Test
    @DisplayName("alt text is editable without uploading the image again")
    void altTextIsPatchable() {
        TokenPair manager = approvedManager();
        Event event = draftEvent(manager);
        CoverUpload upload = beginUpload(manager, event.getId()).getBody();
        uploadTo(upload, png(), "cover.png");
        URI url = confirm(manager, event.getId(), upload.getUploadId(), "Frist").getBody()
                .getCoverImageUrl();

        var patch = new com.eventticket.api.model.EventPatch();
        patch.setCoverImageAlt("First");
        Event fixed = exchange(HttpMethod.PATCH, "/events/" + event.getId(), manager, patch,
                Event.class).getBody();

        assertThat(fixed.getCoverImageAlt()).isEqualTo("First");
        assertThat(fixed.getCoverImageUrl()).isEqualTo(url);
    }

    /**
     * The key carries the organization and the Event, and both come from a request that has
     * already been checked - so there is no key another tenant can address, and no upload of
     * theirs to confirm.
     */
    @Test
    @DisplayName("another organization cannot upload a cover to an event that is not theirs")
    void coversAreTenantScoped() {
        TokenPair mine = approvedManager();
        Event event = draftEvent(mine);
        TokenPair theirs = approvedManager();

        assertThat(exchange(HttpMethod.POST, "/events/" + event.getId() + "/cover-uploads",
                theirs, null, Error.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(exchange(HttpMethod.DELETE, "/events/" + event.getId() + "/cover", theirs,
                null, Error.class).getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
    }

    @Test
    @DisplayName("a cancelled event refuses an upload before anybody sends five megabytes")
    void aCancelledEventRefusesEarly() {
        TokenPair manager = approvedManager();
        Event event = publishedEvent(manager);
        exchange(HttpMethod.POST, "/events/" + event.getId() + "/cancel", manager,
                new com.eventticket.api.model.RefundRequest("The venue flooded."),
                com.eventticket.api.model.EventCancellation.class);

        var refused = exchange(HttpMethod.POST, "/events/" + event.getId() + "/cover-uploads",
                manager, null, Error.class);
        assertThat(refused.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
        assertThat(refused.getBody().getCode().getValue()).isEqualTo("EVENT_FIELD_FROZEN");
    }

    // ---- the machinery ----

    private org.springframework.http.ResponseEntity<CoverUpload> beginUpload(TokenPair session,
                                                                            UUID eventId) {
        var response = exchange(HttpMethod.POST, "/events/" + eventId + "/cover-uploads", session,
                null, CoverUpload.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        return response;
    }

    private org.springframework.http.ResponseEntity<Event> confirm(TokenPair session, UUID eventId,
                                                                   String uploadId, String alt) {
        var body = new CoverConfirmation(uploadId);
        body.setAlt(alt);
        var response = exchange(HttpMethod.PUT, "/events/" + eventId + "/cover", session, body,
                Event.class);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        return response;
    }

    private URI coverOf(TokenPair session, UUID eventId) {
        return exchange(HttpMethod.GET, "/events/" + eventId, session, null, Event.class)
                .getBody().getCoverImageUrl();
    }

    /**
     * The upload a browser would do: every signed field, then the file, last. S3 reads fields
     * in order and stops at the file, so a form that puts them the other way round is one the
     * store rejects with a message about nothing in particular.
     */
    private int uploadTo(CoverUpload upload, byte[] content, String filename) {
        String boundary = "----" + UUID.randomUUID();
        var body = new ArrayList<byte[]>();
        for (Map.Entry<String, String> field : upload.getFields().entrySet()) {
            body.add(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                    + field.getKey() + "\"\r\n\r\n" + field.getValue() + "\r\n")
                    .getBytes(StandardCharsets.UTF_8));
        }
        body.add(("--" + boundary + "\r\nContent-Disposition: form-data; name=\""
                + upload.getFileField() + "\"; filename=\"" + filename
                + "\"\r\nContent-Type: image/png\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        body.add(content);
        body.add(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));

        // One array rather than several: `ofByteArrays` reports an unknown content length, so
        // the JDK client sends the form chunked - and a store that cannot read a chunked POST
        // answers "request body cannot be empty", which is a long way from what is wrong.
        var whole = new ByteArrayOutputStream();
        body.forEach(part -> whole.write(part, 0, part.length));

        try {
            var response = DIRECT.send(HttpRequest.newBuilder(upload.getUrl())
                            .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                            .POST(HttpRequest.BodyPublishers.ofByteArray(whole.toByteArray()))
                            .build(),
                    HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() >= 400) {
                System.out.println("UPLOAD REFUSED " + response.statusCode() + " " + response.body());
            }
            return response.statusCode();
        } catch (IOException | InterruptedException failed) {
            throw new IllegalStateException("Upload could not be sent", failed);
        }
    }

    private byte[] fetch(URI url) {
        try {
            return DIRECT.send(HttpRequest.newBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.ofByteArray()).body();
        } catch (IOException | InterruptedException failed) {
            throw new IllegalStateException("Could not fetch " + url, failed);
        }
    }

    private int statusOf(URI url) {
        try {
            return DIRECT.send(HttpRequest.newBuilder(url).GET().build(),
                    HttpResponse.BodyHandlers.discarding()).statusCode();
        } catch (IOException | InterruptedException failed) {
            throw new IllegalStateException("Could not reach " + url, failed);
        }
    }

    /** A real PNG, so the leading bytes are the ones a decoder would find. */
    private static byte[] png() {
        try {
            var out = new ByteArrayOutputStream();
            ImageIO.write(new BufferedImage(1, 1, BufferedImage.TYPE_INT_RGB), "png", out);
            return out.toByteArray();
        } catch (IOException impossible) {
            throw new IllegalStateException("PNG encoding is required of every JVM", impossible);
        }
    }

    private Event draftEvent(TokenPair manager) {
        Venue venue = venueWithSeats(manager);
        return createEvent(manager, venue.getId(), "Live in Saigon",
                java.time.OffsetDateTime.now().plusMonths(1));
    }

    private Event publishedEvent(TokenPair manager) {
        Venue venue = venueWithSeats(manager);
        Event event = createEvent(manager, venue.getId(), "Live in Saigon",
                java.time.OffsetDateTime.now().plusMonths(1));
        priceTier(manager, event.getId(), "Standard", 250_000);
        return publish(manager, event.getId(), Event.class).getBody();
    }

    private Venue venueWithSeats(TokenPair manager) {
        Venue venue = createVenue(manager, "Hoa Binh Theatre", "Ho Chi Minh City");
        SeatMap map = SeatMaps.block("Standard", 2, 2);
        putSeatMap(manager, venue.getId(), map);
        return venue;
    }

    private TokenPair approvedManager() {
        TokenPair session = signUp("organizer-" + UUID.randomUUID() + "@example.com");
        var organization = createOrganization(session, "Acme Events " + UUID.randomUUID());
        approve(organization);
        return switchTo(session, organization);
    }
}
