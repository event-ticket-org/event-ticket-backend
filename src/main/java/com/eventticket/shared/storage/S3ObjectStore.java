package com.eventticket.shared.storage;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.springframework.stereotype.Component;
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials;
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider;
import software.amazon.awssdk.core.ResponseBytes;
import software.amazon.awssdk.core.sync.ResponseTransformer;
import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.CopyObjectRequest;
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest;
import software.amazon.awssdk.services.s3.model.GetObjectRequest;
import software.amazon.awssdk.services.s3.model.HeadObjectRequest;
import software.amazon.awssdk.services.s3.model.MetadataDirective;
import software.amazon.awssdk.services.s3.model.NoSuchKeyException;
import software.amazon.awssdk.services.s3.model.S3Exception;

/**
 * The one implementation of {@link ObjectStore}, against anything that speaks S3 - AWS
 * deployed, MinIO in {@code compose.yaml} and in the tests.
 *
 * <p>Path-style addressing, because virtual-host style needs DNS for every bucket and MinIO on
 * localhost has none.
 */
@Component
public class S3ObjectStore implements ObjectStore {

    private static final DateTimeFormatter AMZ_DATE =
            DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter AMZ_DAY =
            DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);

    private final StorageProperties properties;
    private final S3Client client;

    public S3ObjectStore(StorageProperties properties) {
        this.properties = properties;
        var builder = S3Client.builder()
                .region(Region.of(properties.region()))
                .credentialsProvider(StaticCredentialsProvider.create(
                        AwsBasicCredentials.create(properties.accessKey(), properties.secretKey())))
                .forcePathStyle(true);
        if (properties.endpoint() != null && !properties.endpoint().isBlank()) {
            builder.endpointOverride(URI.create(properties.endpoint()));
        }
        this.client = builder.build();
    }

    /**
     * A signature-v4 POST policy, written out by hand.
     *
     * <p>Not an oversight: the Java SDK v2 presigns GET and PUT and has no POST policy at all,
     * where the JavaScript and Python SDKs do. A presigned PUT would have been less code and a
     * worse guarantee - it has no equivalent of {@code content-length-range}, so the ceiling
     * would live in a number the client tells us rather than in a condition the store enforces,
     * and "somebody uploads five gigabytes" would be a thing we asked them not to do.
     *
     * <p>Code that computes a signature is code that is either exactly right or completely
     * broken, and reading it proves neither. {@code CoverImageUploadTest} performs a real
     * upload against a real store; that is what says this works.
     */
    @Override
    public UploadForm presignUpload(String key) {
        Instant now = Instant.now();
        Instant expiresAt = now.plus(properties.uploadValidity());
        String day = AMZ_DAY.format(now);
        String amzDate = AMZ_DATE.format(now);
        String credential = properties.accessKey() + "/" + day + "/" + properties.region()
                + "/s3/aws4_request";

        // Conditions are the whole of the authorisation: this bucket, this exact key, and a
        // body between one byte and the ceiling. Anything the client alters - including the
        // key - stops matching the signature and is refused by the store, not by us.
        String policy = """
                {"expiration":"%s","conditions":[\
                {"bucket":"%s"},\
                {"key":"%s"},\
                {"x-amz-algorithm":"AWS4-HMAC-SHA256"},\
                {"x-amz-credential":"%s"},\
                {"x-amz-date":"%s"},\
                ["content-length-range",1,%d]\
                ]}"""
                .formatted(DateTimeFormatter.ISO_INSTANT.format(expiresAt), properties.bucket(),
                        key, credential, amzDate, properties.maxBytes());
        String encoded = java.util.Base64.getEncoder()
                .encodeToString(policy.getBytes(StandardCharsets.UTF_8));

        byte[] signingKey = hmac(hmac(hmac(hmac(
                ("AWS4" + properties.secretKey()).getBytes(StandardCharsets.UTF_8), day),
                properties.region()), "s3"), "aws4_request");

        // Order matters to nothing but a reader: S3 reads form fields by name. Kept in the
        // order a policy lists them so the two can be compared side by side.
        Map<String, String> fields = new LinkedHashMap<>();
        fields.put("key", key);
        fields.put("x-amz-algorithm", "AWS4-HMAC-SHA256");
        fields.put("x-amz-credential", credential);
        fields.put("x-amz-date", amzDate);
        fields.put("policy", encoded);
        fields.put("x-amz-signature", hex(hmac(signingKey, encoded)));

        return new UploadForm(bucketUrl(), fields, "file", properties.maxBytes(), expiresAt);
    }

    @Override
    public Optional<StoredObject> describe(String key) {
        try {
            var head = client.headObject(HeadObjectRequest.builder()
                    .bucket(properties.bucket()).key(key).build());
            return Optional.of(new StoredObject(head.contentType(), head.contentLength()));
        } catch (NoSuchKeyException absent) {
            return Optional.empty();
        } catch (S3Exception maybeAbsent) {
            // A HEAD carries no body, so S3 cannot say *which* error it is and the SDK cannot
            // raise NoSuchKeyException - a missing object arrives as a bare 404. Anything else
            // is a real failure and is not this method's to swallow.
            if (maybeAbsent.statusCode() == 404) {
                return Optional.empty();
            }
            throw maybeAbsent;
        }
    }

    @Override
    public byte[] readLeadingBytes(String key, int count) {
        ResponseBytes<?> bytes = client.getObject(GetObjectRequest.builder()
                        .bucket(properties.bucket()).key(key)
                        .range("bytes=0-" + (count - 1)).build(),
                ResponseTransformer.toBytes());
        return bytes.asByteArray();
    }

    @Override
    public void promote(String fromKey, String toKey, String contentType) {
        client.copyObject(CopyObjectRequest.builder()
                .sourceBucket(properties.bucket()).sourceKey(fromKey)
                .destinationBucket(properties.bucket()).destinationKey(toKey)
                // REPLACE, so the copy carries the type the bytes say it is rather than
                // whatever the upload happened to declare.
                .metadataDirective(MetadataDirective.REPLACE)
                .contentType(contentType)
                .build());
        delete(fromKey);
    }

    @Override
    public void delete(String key) {
        client.deleteObject(DeleteObjectRequest.builder()
                .bucket(properties.bucket()).key(key).build());
    }

    @Override
    public String publicUrl(String key) {
        return properties.publicBaseUrl().replaceAll("/+$", "") + "/" + key;
    }

    private String bucketUrl() {
        String base = properties.endpoint() == null || properties.endpoint().isBlank()
                ? "https://s3." + properties.region() + ".amazonaws.com"
                : properties.endpoint();
        return base.replaceAll("/+$", "") + "/" + properties.bucket();
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException impossible) {
            throw new IllegalStateException("HmacSHA256 is required of every JVM", impossible);
        }
    }

    private static String hex(byte[] bytes) {
        StringBuilder out = new StringBuilder(bytes.length * 2);
        for (byte b : bytes) {
            out.append(Character.forDigit((b >> 4) & 0xF, 16)).append(Character.forDigit(b & 0xF, 16));
        }
        return out.toString();
    }
}
