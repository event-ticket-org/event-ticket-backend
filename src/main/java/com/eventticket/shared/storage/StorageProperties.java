package com.eventticket.shared.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where objects live and what may be put there.
 *
 * <p>Three addresses, and they are three because they are three different things. They only
 * look like duplication on a laptop, where all of them are localhost.
 *
 * <ul>
 *   <li>{@code endpoint} is how <em>this application</em> reaches the store: empty for AWS
 *       itself, MinIO locally, and in a container a name only the container network resolves.
 *   <li>{@code publicBaseUrl} is where <em>a browser fetches</em> a cover. It may be a CDN,
 *       which can serve a picture and cannot accept one.
 *   <li>{@code uploadBaseUrl} is where <em>a browser POSTs</em> one, so it has to be the
 *       bucket and it has to be reachable from outside. Empty falls back to {@code endpoint},
 *       which is what makes a local run and a deployment the same code path.
 * </ul>
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(String endpoint, String region, String bucket,
                                String accessKey, String secretKey, String publicBaseUrl,
                                String uploadBaseUrl, long maxBytes, Duration uploadValidity) {
}
