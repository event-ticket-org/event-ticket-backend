package com.eventticket.shared;

import static org.assertj.core.api.Assertions.assertThat;

import com.eventticket.api.model.Error;
import com.eventticket.support.ApiTest;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

/**
 * A request a client got wrong is the client's fault, and has to say so.
 *
 * <p>Every case here used to answer <strong>500, "The request could not be completed"</strong>
 * and log at ERROR as an unhandled exception, because the catch-all in
 * {@code ApiExceptionHandler} caught Spring's own web exceptions along with real defects. Two
 * things were wrong with that and the second is the worse one:
 *
 * <ul>
 *   <li>A caller is told the server broke when the server understood perfectly and is refusing.
 *       "Something went wrong" sends somebody hunting through server logs for a typo in their
 *       own request.
 *   <li>Every one of these wrote an ERROR with a stack trace. A client looping on a wrong URL
 *       buries the real defects in the same log - which is exactly when the log matters.
 * </ul>
 *
 * <p>None of these carry a new error code. {@code VALIDATION_FAILED} is the contract's "your
 * request was wrong", and a client branches on the code for <em>domain</em> outcomes -
 * SEATS_UNAVAILABLE against ORDER_ALREADY_PAID. None of these are outcomes; they are a request
 * that never reached a use case, and the status is what carries the distinction.
 */
class ClientMistakesTest extends ApiTest {

    @Test
    @DisplayName("the wrong method on a real path is 405, not a server error")
    void theWrongMethodIsNotAServerError() {
        ResponseEntity<Error> response =
                exchange(HttpMethod.GET, "/auth/login", null, null, Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.METHOD_NOT_ALLOWED);
        assertThat(response.getBody().getMessage()).contains("GET");
        // The header is the point of a 405 - it tells the caller what to do instead, and it is
        // the reason this belongs to the framework rather than to a hand-written branch.
        assertThat(response.getHeaders().getAllow()).contains(HttpMethod.POST);
    }

    @Test
    @DisplayName("a body that is not JSON is 400, not a server error")
    void anUnreadableBodyIsNotAServerError() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        ResponseEntity<Error> response = http.exchange("/auth/login", HttpMethod.POST,
                new HttpEntity<>("{\"email\": ", headers), Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    @DisplayName("a body in the wrong format is 415, not a server error")
    void anUnsupportedContentTypeIsNotAServerError() {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.TEXT_PLAIN);

        ResponseEntity<Error> response = http.exchange("/auth/login", HttpMethod.POST,
                new HttpEntity<>("hello", headers), Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNSUPPORTED_MEDIA_TYPE);
    }

    /**
     * Every id in this API is a UUID, and one that is not is a request that could never have
     * meant anything - so it is refused before a use case sees it rather than looked up.
     */
    @Test
    @DisplayName("an id that is not a UUID is 400, not a server error")
    void aMalformedIdIsNotAServerError() {
        ResponseEntity<Error> response = exchange(HttpMethod.GET,
                "/public/events/not-a-uuid", null, null, Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    /**
     * The exception to all of the above, and it is not an oversight: an unknown path answers
     * 401 rather than 404, because security runs before dispatch and nothing is public by
     * default. That is worth keeping - a 404 here would let anybody map which endpoints this
     * deployment has by trying them, which is reconnaissance the API has no reason to help
     * with. The cases above are all on paths a caller has already been allowed to reach.
     */
    @Test
    @DisplayName("an unknown path is refused before it is answered, so nothing is enumerable")
    void anUnknownPathIsRefusedRatherThanDescribed() {
        ResponseEntity<Error> response =
                exchange(HttpMethod.GET, "/no-such-endpoint", null, null, Error.class);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
    }
}
