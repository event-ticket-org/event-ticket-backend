package com.eventticket.shared.storage;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where objects live and what may be put there.
 *
 * <p>{@code endpoint} is empty for AWS itself and set for anything that speaks S3 at another
 * address - MinIO locally. {@code publicBaseUrl} is deliberately separate from it: the
 * application reaches the store over its own network while a browser reaches it over the
 * internet, and the two stop being the same address the moment anything is put in front of
 * the bucket.
 */
@ConfigurationProperties("app.storage")
public record StorageProperties(String endpoint, String region, String bucket,
                                String accessKey, String secretKey, String publicBaseUrl,
                                long maxBytes, Duration uploadValidity) {
}
